package moe.rukamori.archivetune.startup

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import kotlin.system.exitProcess
import moe.rukamori.archivetune.BuildConfig

object StartupRecovery {
    private var crashHandler: StartupCrashHandler? = null
    private var attempt: StartupAttempt? = null

    @Synchronized
    fun installExceptionHandler(context: Context) {
        val installed = crashHandler ?: StartupCrashHandler(
            capture = { thread, error ->
                if (!isRecoveryProcess(context)) {
                    val build = CrashBuild(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE.toLong(),
                        process = processName(context).orEmpty(),
                        pid = Process.myPid(),
                    )
                    store(context).captureJava(System.currentTimeMillis(), thread.name, error, build)
                }
            },
            previous = Thread.getDefaultUncaughtExceptionHandler(),
            terminate = {
                Process.killProcess(Process.myPid())
                exitProcess(2)
            },
        ).also { crashHandler = it }
        Thread.setDefaultUncaughtExceptionHandler(installed)
    }

    fun setApplicationCrashHandler(handler: Thread.UncaughtExceptionHandler) {
        crashHandler?.applicationHandler = handler
    }

    internal fun store(context: Context): StartupStateStore =
        StartupStateStore(File(context.noBackupFilesDir, "startup_recovery"))

    internal fun beginStartup(context: Context) {
        if (isRecoveryProcess(context)) return
        val install = currentInstall(context)
        val current = StartupAttempt(
            timestampMs = System.currentTimeMillis(),
            pid = Process.myPid(),
            version = BuildConfig.VERSION_NAME,
            versionCode = install.versionCode,
            installedSinceMs = install.installedSinceMs,
        )
        attempt = current
        runCatching { store(context).begin(current) }
    }

    fun isRecoveryProcess(context: Context): Boolean =
        processName(context)?.endsWith(":crash") == true

    private fun processName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        return runCatching {
            File("/proc/self/cmdline").inputStream().use { it.readCappedText(256).text.substringBefore('\u0000') }
        }.getOrNull() ?: runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }.getOrNull()
    }

    fun watchHealthyStartup(application: Application) {
        val current = attempt ?: return
        val handler = Handler(Looper.getMainLooper())
        val resumed = mutableSetOf<Activity>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            private val healthy = Runnable {
                if (resumed.any { !it.isFinishing && !it.isDestroyed }) {
                    runCatching { store(application).markHealthy(current) }
                    application.unregisterActivityLifecycleCallbacks(this)
                }
            }

            override fun onActivityResumed(activity: Activity) {
                resumed += activity
                handler.removeCallbacks(healthy)
                handler.postDelayed(healthy, HEALTHY_DELAY_MS)
            }
            override fun onActivityPaused(activity: Activity) {
                resumed -= activity
                if (resumed.isEmpty()) handler.removeCallbacks(healthy)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                resumed -= activity
            }
        }
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    private const val HEALTHY_DELAY_MS = 5_000L
}
