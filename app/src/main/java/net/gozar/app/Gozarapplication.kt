package net.gozar.app

import android.app.Activity
import android.os.Bundle
import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class GozarApplication : org.strongswan.android.logic.StrongSwanApplication() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val foreground = MutableStateFlow(false)
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()

        val baseDir = filesDir.also { it.mkdirs() }
        val tempDir = cacheDir.also { it.mkdirs() }
        Libbox.setup(SetupOptions().apply {
            basePath = baseDir.absolutePath
            workingPath = baseDir.absolutePath
            tempPath = tempDir.absolutePath
            fixAndroidStack = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            logMaxLines = 3000
            debug = BuildConfig.DEBUG
            oomKillerEnabled = true
            oomMemoryLimit = 0
        })

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                foreground.value = startedActivities > 0
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                foreground.value = startedActivities > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

    }
}