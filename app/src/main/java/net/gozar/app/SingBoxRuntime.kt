package net.gozar.app

import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.OsConstants
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import java.net.Inet6Address
import java.net.NetworkInterface as SystemNetworkInterface

/**
 * Owns the sing-box CommandServer and bridges its TUN callback to Android VpnService.
 * This adapter intentionally implements only the Android VPN platform surface;
 * optional desktop/SSH/bridge integrations are left disabled.
 */
internal class SingBoxRuntime(private val vpn: GozarVpnService) {
    private val platform = AndroidPlatform()
    private val handler = Handler()
    private var commandServer: CommandServer? = null

    @Synchronized
    fun start(config: String) {
        check(commandServer == null) { "sing-box is already running" }
        val server = CommandServer(handler, platform)
        try {
            server.start()
            server.startOrReloadService(config, OverrideOptions())
            commandServer = server
            Log.i(TAG, "sing-box service started")
        } catch (t: Throwable) {
            runCatching { server.closeService() }
            runCatching { server.close() }
            throw t
        }
    }

    @Synchronized
    fun stop() {
        val server = commandServer ?: return
        commandServer = null
        runCatching { server.closeService() }
            .onFailure { Log.w(TAG, "closeService failed", it) }
        runCatching { server.close() }
            .onFailure { Log.w(TAG, "CommandServer.close failed", it) }
    }

    private inner class Handler : CommandServerHandler {
        override fun serviceStop() {
            vpn.stopFromSingBox()
        }

        override fun serviceReload() {
            Log.w(TAG, "sing-box requested reload; reconnect from the app")
        }

        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

        override fun setSystemProxyEnabled(isEnabled: Boolean) {}

        override fun connectSSHAgent(): Int =
            throw UnsupportedOperationException("SSH agent is not available on Android")

        override fun triggerNativeCrash() {}

        override fun writeDebugMessage(message: String) {
            Log.i(TAG, "sing-box: $message")
        }
    }

    private inner class AndroidPlatform : PlatformInterface {
        override fun sendNotification(notification: Notification) {}
        override fun localDNSTransport(): LocalDNSTransport? = null
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun autoDetectInterfaceControl(fd: Int) { vpn.protect(fd) }

        override fun openTun(options: TunOptions): Int {
            val builder = vpn.Builder()
                .setSession("KimiVPN")
                .setMtu(options.mtu.coerceIn(576, 9000))

            val v4 = options.inet4Address
            while (v4.hasNext()) {
                val address = v4.next()
                builder.addAddress(address.address(), address.prefix())
            }
            val v6 = options.inet6Address
            while (v6.hasNext()) {
                val address = v6.next()
                builder.addAddress(address.address(), address.prefix())
            }

            if (options.autoRoute) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) {
                    val server = dns.next()
                    if (server.isNotBlank()) builder.addDnsServer(server)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val routes4 = options.inet4RouteAddress
                    while (routes4.hasNext()) {
                        val route = routes4.next()
                        builder.addRoute(route.address(), route.prefix())
                    }
                    val routes6 = options.inet6RouteAddress
                    while (routes6.hasNext()) {
                        val route = routes6.next()
                        builder.addRoute(route.address(), route.prefix())
                    }
                } else {
                    val routes4 = options.inet4RouteRange
                    while (routes4.hasNext()) {
                        val route = routes4.next()
                        builder.addRoute(route.address(), route.prefix())
                    }
                    val routes6 = options.inet6RouteRange
                    while (routes6.hasNext()) {
                        val route = routes6.next()
                        builder.addRoute(route.address(), route.prefix())
                    }
                }

                val included = options.includePackage
                while (included.hasNext()) runCatching { builder.addAllowedApplication(included.next()) }
                val excluded = options.excludePackage
                while (excluded.hasNext()) runCatching { builder.addDisallowedApplication(excluded.next()) }
            }

            val descriptor = builder.establish() ?: error("Android refused to establish the VPN interface")
            vpn.onSingBoxTunEstablished(descriptor)
            return descriptor.fd
        }

        override fun findConnectionOwner(
            ipProtocol: Int, sourceAddress: String, sourcePort: Int,
            destinationAddress: String, destinationPort: Int
        ): ConnectionOwner = ConnectionOwner().apply { userId = android.os.Process.INVALID_UID }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
        override fun getInterfaces(): NetworkInterfaceIterator {
            val result = runCatching {
                val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
                val systemInterfaces = SystemNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                connectivity.allNetworks.mapNotNull { network ->
                    val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
                    val interfaceName = properties.interfaceName ?: return@mapNotNull null
                    val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                    val system = systemInterfaces.firstOrNull { it.name == interfaceName }
                        ?: return@mapNotNull null
                    io.nekohasekai.libbox.NetworkInterface().apply {
                        name = interfaceName
                        dnsServer = StringArray(properties.dnsServers.mapNotNull { it.hostAddress }.iterator())
                        type = when {
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                                io.nekohasekai.libbox.Libbox.InterfaceTypeWIFI
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                                io.nekohasekai.libbox.Libbox.InterfaceTypeCellular
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                                io.nekohasekai.libbox.Libbox.InterfaceTypeEthernet
                            else -> io.nekohasekai.libbox.Libbox.InterfaceTypeOther
                        }
                        index = system.index
                        runCatching { mtu = system.mtu }
                        addresses = StringArray(system.interfaceAddresses.map { address ->
                            val host = if (address.address is Inet6Address) {
                                address.address.hostAddress.substringBefore('%')
                            } else address.address.hostAddress
                            "$host/${address.networkPrefixLength}"
                        }.iterator())
                        var interfaceFlags = 0
                        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            interfaceFlags = interfaceFlags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                        }
                        if (system.isLoopback) interfaceFlags = interfaceFlags or OsConstants.IFF_LOOPBACK
                        if (system.isPointToPoint) interfaceFlags = interfaceFlags or OsConstants.IFF_POINTOPOINT
                        if (system.supportsMulticast()) interfaceFlags = interfaceFlags or OsConstants.IFF_MULTICAST
                        flags = interfaceFlags
                        metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }
                }
            }.getOrElse {
                Log.w(TAG, "getInterfaces failed: ${it.message}")
                emptyList()
            }
            return object : NetworkInterfaceIterator {
                private val iterator = result.iterator()
                override fun hasNext(): Boolean = iterator.hasNext()
                override fun next(): io.nekohasekai.libbox.NetworkInterface =
                    if (iterator.hasNext()) iterator.next() else io.nekohasekai.libbox.NetworkInterface()
            }
        }

        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun clearDNSCache() {}
        override fun readWIFIState(): WIFIState? = null
        override fun registerMyInterface(name: String) {}
        override fun usePlatformShell(): Boolean = false
        override fun checkPlatformShell() {}
        override fun lookupUser(username: String): PlatformUser = PlatformUser()
        override fun lookupSFTPServer(): String = ""
        override fun readSystemSSHHostKey(): String = ""
        override fun tailscaleHostname(): String = ""
        override fun openShellSession(
            user: PlatformUser, command: String, env: StringIterator,
            termType: String, width: Int, height: Int
        ): ShellSession = throw UnsupportedOperationException("shell is not supported")
        override fun startNeighborMonitor(listener: NeighborUpdateListener) {}
        override fun closeNeighborMonitor(listener: NeighborUpdateListener) {}
        override fun usePlatformBridge(): Boolean = false
        override fun cancelNotification(identifier: String, typeID: Int) {}
        override fun createBridge(options: BridgeOptions): BridgeSession =
            throw UnsupportedOperationException("platform bridge is not supported")
    }


    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = if (iterator.hasNext()) iterator.next() else ""
        override fun len(): Int = 0
    }

    companion object { private const val TAG = "SingBoxRuntime" }
}
