package app.natune.startup

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import app.natune.backup.AndroidRestoreCoordinator

abstract class StartupApplication : Application() {
    override fun attachBaseContext(base: Context) {
        StartupRecovery.installExceptionHandler(base)
        super.attachBaseContext(base)
        if (!StartupRecovery.isRecoveryProcess(base)) {
            AndroidRestoreCoordinator.recoverAtStartup(restoreContext(this))
        }
        // Providers run between attachBaseContext and onCreate, before Hilt's application injection.
        StartupRecovery.beginStartup(base)
    }

    // Calling App's generated Hilt super in :crash would instantiate the very graph being diagnosed.
    protected fun onCreateWithoutInjection() {
        super.onCreate()
    }
}

/**
 * The context a staged restore is applied with. Inside attachBaseContext the platform has not registered the
 * Application yet, so `getApplicationContext()` is still null; the `Context.dataStore` delegate builds its
 * process-wide instance from that value, so applying settings there failed, rolled the whole restore back, and
 * left the app holding a DataStore it could not open. The Application itself is already usable here.
 */
internal fun restoreContext(application: Application): Context =
    object : ContextWrapper(application) {
        override fun getApplicationContext(): Context = application
    }
