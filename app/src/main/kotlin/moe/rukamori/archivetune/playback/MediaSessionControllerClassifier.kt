package moe.rukamori.archivetune.playback

import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import androidx.media3.session.MediaSession

/**
 * Maps a connecting [MediaSession.ControllerInfo] to a [MediaControllerTier]. The assistant and
 * notification-listener checks read [Settings.Secure] on each connect: both can change while the
 * service is alive and a connect is rare enough that the binder read is cheap.
 */
class MediaSessionControllerClassifier(
    context: Context,
) {
    private val contentResolver = context.contentResolver
    private val appUid: Int = Process.myUid()

    fun tierOf(controller: MediaSession.ControllerInfo): MediaControllerTier =
        MediaSessionAuthorization.tierOf(
            packageName = controller.packageName,
            uid = controller.uid,
            appUid = appUid,
            isSystemTrusted = controller.isTrusted,
            isNotificationListener = isEnabledNotificationListener(controller.packageName),
            isAssistant = isAssistantPackage(controller.packageName),
        )

    private fun secureSetting(name: String): String? =
        runCatching { Settings.Secure.getString(contentResolver, name) }.getOrNull()

    private fun componentPackage(flattened: String?): String? =
        flattened
            ?.takeIf { it.isNotBlank() }
            ?.let(ComponentName::unflattenFromString)
            ?.packageName

    private fun isAssistantPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return listOf(ASSISTANT_SETTING, VOICE_INTERACTION_SERVICE_SETTING).any { key ->
            componentPackage(secureSetting(key)) == packageName
        }
    }

    private fun isEnabledNotificationListener(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val listeners = secureSetting(ENABLED_NOTIFICATION_LISTENERS_SETTING) ?: return false
        return listeners
            .split(':')
            .any { componentPackage(it) == packageName }
    }

    private companion object {
        const val ASSISTANT_SETTING = "assistant"
        const val VOICE_INTERACTION_SERVICE_SETTING = "voice_interaction_service"
        const val ENABLED_NOTIFICATION_LISTENERS_SETTING = "enabled_notification_listeners"
    }
}
