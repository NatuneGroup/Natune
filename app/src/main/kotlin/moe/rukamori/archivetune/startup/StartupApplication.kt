package moe.rukamori.archivetune.startup

import android.app.Application
import android.content.Context

abstract class StartupApplication : Application() {
    override fun attachBaseContext(base: Context) {
        StartupRecovery.installExceptionHandler(base)
        super.attachBaseContext(base)
        // Providers run between attachBaseContext and onCreate, before Hilt's application injection.
        StartupRecovery.beginStartup(base)
    }

    // Calling App's generated Hilt super in :crash would instantiate the very graph being diagnosed.
    protected fun onCreateWithoutInjection() {
        super.onCreate()
    }
}
