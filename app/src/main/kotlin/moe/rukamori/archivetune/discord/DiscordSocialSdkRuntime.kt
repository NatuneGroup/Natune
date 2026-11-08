package moe.rukamori.archivetune.discord

import android.app.Activity
import android.app.Application
import android.os.Bundle
import moe.rukamori.archivetune.BuildConfig

internal object DiscordSocialSdkRuntime {
    /** The AAR's only Java integration hook; absence is expected in SDK-free builds. */
    private fun setEngineActivity(activity: Activity) {
        if (!BuildConfig.DISCORD_SOCIAL_SDK_AVAILABLE) return
        runCatching {
            Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
                .getMethod("setEngineActivity", Activity::class.java).invoke(null, activity)
        }
    }

    /** Registers the engine activity before the launcher renders; a no-op in SDK-free builds. */
    fun register(application: Application) {
        if (!BuildConfig.DISCORD_SOCIAL_SDK_AVAILABLE) return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity is moe.rukamori.archivetune.MainActivity) setEngineActivity(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    fun forwardSdkCallback(activity: Activity, data: android.net.Uri?): Boolean {
        if (!DiscordNativeSdkBridge.available || !DiscordNativeSdkBridge.authorizationInProgress || data == null) return false
        activity.startActivity(android.content.Intent().setClassName(activity, "com.discord.socialsdk.AuthenticationActivity").setData(data))
        return true
    }
}
