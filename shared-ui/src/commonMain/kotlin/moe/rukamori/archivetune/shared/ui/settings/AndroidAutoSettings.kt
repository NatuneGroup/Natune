package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Host projection of the car connection; the host maps its platform signal (projection, native car or none). */
enum class AndroidAutoConnection { DISCONNECTED, PROJECTION, NATIVE }

/**
 * Android Auto settings. [hasLocalAudioPermission] and [connection] are host observations. Enabling device songs
 * without the audio permission calls [requestAudioPermission] after the commit; [openAppPermissions] opens the OS
 * app-settings screen. A failed host action reports through [onFailure] and never reads as success.
 */
@Composable
fun AndroidAutoSettings(
    controller: SettingsController,
    connection: AndroidAutoConnection,
    hasLocalAudioPermission: Boolean,
    requestAudioPermission: suspend () -> Unit,
    openAppPermissions: suspend () -> Unit,
    onFailure: () -> Unit,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    val connectionText = stringResource(connection.labelResource())
    val localSongsNeedPermission = preferences[AndroidAutoLocalSongsKey, true] && !hasLocalAudioPermission
    val rows = buildList<SettingsRow> {
        add(SettingsRow.Header("android_auto_connection", Res.string.m6_android_auto_connection))
        add(SettingsRow.Status("android_auto_status", Res.string.m6_android_auto_title, connectionText))
        add(SettingsRow.Action("android_auto_app_permissions", Res.string.m6_android_auto_app_permissions, run = {
            scope.launch {
                try { openAppPermissions() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
            }
        }))
        add(SettingsRow.Header("android_auto_content", Res.string.m6_android_auto_content))
        add(SettingsRow.Toggle(
            "android_auto_online_recommendations", Res.string.m6_android_auto_online_recommendations,
            AndroidAutoOnlineRecommendationsKey, true,
            description = Res.string.m6_android_auto_online_recommendations_desc,
        ))
        add(SettingsRow.Toggle(
            "android_auto_online_voice_search", Res.string.m6_android_auto_online_voice_search,
            AndroidAutoOnlineVoiceSearchKey, true,
            description = Res.string.m6_android_auto_online_voice_search_desc,
        ))
        add(SettingsRow.Toggle(
            "android_auto_local_songs", Res.string.m6_android_auto_local_songs,
            AndroidAutoLocalSongsKey, true,
            description = if (localSongsNeedPermission) Res.string.m6_android_auto_audio_permission_missing
            else Res.string.m6_android_auto_local_songs_desc,
        ))
        add(SettingsRow.Header("android_auto_data", Res.string.m6_android_auto_data))
        add(SettingsRow.Toggle(
            "android_auto_metered_playback", Res.string.m6_android_auto_metered_playback,
            AndroidAutoMeteredPlaybackKey, true,
            description = Res.string.m6_android_auto_metered_playback_desc,
        ))
        add(SettingsRow.Toggle(
            "android_auto_metered_artwork", Res.string.m6_android_auto_metered_artwork,
            AndroidAutoMeteredArtworkKey, true,
            description = Res.string.m6_android_auto_metered_artwork_desc,
        ))
        add(SettingsRow.Header("android_auto_controls", Res.string.m6_android_auto_controls))
        add(SettingsRow.Choice(
            "android_auto_primary_action", Res.string.m6_android_auto_primary_action,
            AndroidAutoPrimaryActionKey, AndroidAutoCustomAction.LIKE.name, primaryActionOptions,
            write = { stored ->
                setAndroidAutoAction(
                    AndroidAutoActionSlot.PRIMARY,
                    AndroidAutoCustomAction.fromPreference(stored, AndroidAutoCustomAction.LIKE),
                )
            },
        ))
        add(SettingsRow.Choice(
            "android_auto_secondary_action", Res.string.m6_android_auto_secondary_action,
            AndroidAutoSecondaryActionKey, AndroidAutoCustomAction.START_RADIO.name, secondaryActionOptions,
            write = { stored ->
                setAndroidAutoAction(
                    AndroidAutoActionSlot.SECONDARY,
                    AndroidAutoCustomAction.fromPreference(stored, AndroidAutoCustomAction.START_RADIO),
                )
            },
        ))
    }
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        resumed = resumed,
        motionEnabled = motionEnabled,
        onFailure = onFailure,
        modifier = modifier,
        onCommitted = { change ->
            if (AndroidAutoLocalSongsKey in change.changedKeys &&
                change.after[AndroidAutoLocalSongsKey, true] &&
                !hasLocalAudioPermission
            ) {
                requestAudioPermission()
            }
        },
    )
}

private val primaryActionOptions = listOf(
    SettingsRow.Option(AndroidAutoCustomAction.LIKE.name, Res.string.m6_android_auto_action_like),
    SettingsRow.Option(AndroidAutoCustomAction.START_RADIO.name, Res.string.m6_android_auto_action_radio),
    SettingsRow.Option(AndroidAutoCustomAction.SHUFFLE.name, Res.string.m6_android_auto_action_shuffle),
    SettingsRow.Option(AndroidAutoCustomAction.REPEAT.name, Res.string.m6_android_auto_action_repeat),
)

/** The secondary slot may be cleared; the primary slot may not. */
private val secondaryActionOptions = primaryActionOptions +
    SettingsRow.Option(AndroidAutoCustomAction.NONE.name, Res.string.m6_android_auto_action_none)

private fun AndroidAutoConnection.labelResource(): StringResource = when (this) {
    AndroidAutoConnection.DISCONNECTED -> Res.string.m6_android_auto_disconnected
    AndroidAutoConnection.PROJECTION -> Res.string.m6_android_auto_projection
    AndroidAutoConnection.NATIVE -> Res.string.m6_android_auto_native
}
