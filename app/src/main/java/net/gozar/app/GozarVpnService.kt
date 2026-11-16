package net.gozar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GozarVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var singBoxRuntime: SingBoxRuntime? = null
    private var proxyConfig: ProxyConfig? = null
    private var aetherSpec: AetherSpec? = null
    private var torSpec: String? = null
    private var blockFd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var pollJob: Job? = null
    private var configName: String = "VPN"
    private var stopLabel: String = "Disconnect"
    @Volatile private var tearingDown = false
    private var autoSelector: AutoSelector? = null
    private var autoJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        TorLog.sink = { line -> Log.i("KimiVPN", line) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runCatching { blockFd?.close() }; blockFd = null
                die(null)
                return START_NOT_STICKY
            }
            ACTION_WARM -> {
                if (tunFd != null) {
                    VpnBridge.sendConnected(applicationContext)
                    return START_STICKY
                }
                return START_NOT_STICKY
            }
            else -> {
                val configJson = intent?.getStringExtra(EXTRA_CONFIG)
                aetherSpec = AetherSpec.parse(intent?.getStringExtra(EXTRA_AETHER))
                torSpec = intent?.getStringExtra(EXTRA_TOR)
                configName = intent?.getStringExtra(EXTRA_NAME) ?: "VPN"
                stopLabel = intent?.getStringExtra(EXTRA_STOP_LABEL) ?: "Disconnect"
                proxyConfig = intent?.getStringExtra(EXTRA_PROXY_CONFIG)?.let {
                    runCatching { ProxyConfig.fromJson(org.json.JSONObject(it)) }.getOrNull()
                }
                if (configJson.isNullOrEmpty() || proxyConfig == null) {
                    die("No config provided")
                    return START_NOT_STICKY
                }
                startTunnel(configJson)
            }
        }
        return START_STICKY
    }

    private fun startTunnel(configJson: String) {
        if (singBoxRuntime != null) return
        tearingDown = false
        startForeground(NOTIF_ID, buildNotification())

        scope.launch {
            try {
                val config = proxyConfig ?: error("Missing proxy profile")
                if (config.protocol.lowercase() !in setOf("vless", "vmess", "trojan", "freedom")) {
                    error("sing-box migration currently supports VLESS, VMess and Trojan profiles")
                }
                if (aetherSpec != null || torSpec != null) {
                    error("Aether and Tor routing are not yet migrated to sing-box")
                }
                val runtime = SingBoxRuntime(this@GozarVpnService)
                singBoxRuntime = runtime
                runtime.start(
                    if (config.protocol.equals("freedom", true)) {
                        SingBoxConfigBuilder.buildDirectOnly()
                    } else {
                        SingBoxConfigBuilder.build(config)
                    }
                )
                Log.i(TAG, "sing-box core started, tunnel up")
                VpnBridge.sendConnected(applicationContext)
            } catch (e: Throwable) {
                Log.e(TAG, "sing-box core failed to start", e)
                runCatching { singBoxRuntime?.stop() }
                singBoxRuntime = null
                runCatching { tunFd?.close() }
                tunFd = null
                die(e.message ?: "sing-box engine failed to start")
            }
        }
    }

    internal fun onSingBoxTunEstablished(descriptor: ParcelFileDescriptor) {
        runCatching { tunFd?.close() }
        tunFd = descriptor
    }

    internal fun stopFromSingBox() {
        if (!tearingDown) die(null)
    }

    private fun startAutoSelect() {
        if (autoJob?.isActive == true) return
        val store = ConfigStore.get(applicationContext)
        val selector = AutoSelector(applicationContext, store) { target ->
            switchTunnel(target)
        }
        autoSelector = selector
        autoJob = scope.launch {
            store.autoSelect.collect { on ->
                if (on) selector.start(this) else selector.stop()
            }
        }
    }

    private fun stopAutoSelect() {
        autoJob?.cancel(); autoJob = null
        autoSelector?.stop(); autoSelector = null
    }

    private fun switchTunnel(config: ProxyConfig) {
        if (tearingDown) return
        val store = ConfigStore.get(applicationContext)
        val json = SingBoxConfigBuilder.build(config)
        proxyConfig = config
        Log.d(TAG, "switching tunnel to ${config.name}")
        pollJob?.cancel(); pollJob = null
        runCatching { singBoxRuntime?.stop() }; singBoxRuntime = null
        AetherController.stop()
        TorController.stop()
        runCatching { tunFd?.close() }; tunFd = null
        aetherSpec = AetherSpec.from(config)
        torSpec = if (config.protocol == "tor")
            config.torCountry + "|" + (if (config.torThroughVpn) "1" else "0") else null
        configName = config.name
        VpnState.setConnecting(config.id)
        startTunnel(json)
    }

    private fun applyPerApp(builder: Builder) {
        val store = ConfigStore.get(applicationContext)
        val mode = store.perAppMode.value
        val list = store.perAppList.value

        when (mode) {
            PerAppMode.ALLOWLIST -> {
                if (list.isEmpty()) {
                    runCatching { builder.addDisallowedApplication(packageName) }
                } else {
                    list.forEach { pkg ->
                        runCatching { builder.addAllowedApplication(pkg) }
                    }
                }
            }
            PerAppMode.BLOCKLIST -> {
                (list + packageName).forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
            PerAppMode.OFF -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
        }
    }

    private fun die(error: String?) {
        if (tearingDown) return
        val killOn = runCatching { ConfigStore.get(applicationContext).killSwitch.value }.getOrDefault(false)
        if (error != null && killOn) {
            AetherController.stop()
            TorController.stop()
            enterKillSwitch(error)
            return
        }
        tearingDown = true
        stopAutoSelect()
        pollJob?.cancel()
        pollJob = null
        runCatching { singBoxRuntime?.stop() }; singBoxRuntime = null
        runCatching { tunFd?.close() }; tunFd = null
        AetherController.stop()
        TorController.stop()
        if (error != null) VpnBridge.sendError(applicationContext, error)
        else VpnBridge.sendDisconnected(applicationContext)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
        stopSelf()
        scope.launch {
            delay(60)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun enterKillSwitch(reason: String) {
        pollJob?.cancel(); pollJob = null
        runCatching { singBoxRuntime?.stop() }; singBoxRuntime = null
        runCatching { tunFd?.close() }; tunFd = null
        val b = Builder()
            .setSession("GozarNet (blocked)")
            .setMtu(1500)
            .addAddress("10.10.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        applyPerApp(b)
        blockFd = runCatching { b.establish() }.getOrNull()
        VpnBridge.sendError(applicationContext, reason)
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, buildBlockedNotification())
        }
    }

    override fun onDestroy() {
        stopAutoSelect()
        AetherController.stop()
        TorController.stop()
        runCatching { singBoxRuntime?.stop() }; singBoxRuntime = null
        runCatching { tunFd?.close() }; tunFd = null
        runCatching { blockFd?.close() }; blockFd = null
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
        super.onDestroy()
    }

    private fun buildNotification(downSpeed: Long = 0, upSpeed: Long = 0): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GozarNet", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, GozarVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val speedLine = "↓ ${fmt(downSpeed)}/s   ↑ ${fmt(upSpeed)}/s"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(configName)
            .setContentText(speedLine)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, stopLabel, stopPi
                ).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun buildBlockedNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GozarNet", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, GozarVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Kill switch active")
            .setContentText("Connection lost — internet is blocked to prevent leaks")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, stopLabel, stopPi
                ).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun fmt(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    companion object {
        private const val TAG = "GozarVpnService"
        private const val CHANNEL_ID = "gozarnet_vpn"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "net.gozar.app.STOP"
        const val ACTION_WARM = "net.gozar.app.WARM"
        const val EXTRA_CONFIG = "net.gozar.app.CONFIG"
        const val EXTRA_PROXY_CONFIG = "net.gozar.app.PROXY_CONFIG"
        const val EXTRA_AETHER = "net.gozar.app.AETHER"
        const val EXTRA_TOR = "net.gozar.app.TOR"
        const val EXTRA_NAME = "net.gozar.app.NAME"
        const val EXTRA_STOP_LABEL = "net.gozar.app.STOP_LABEL"
    }
}