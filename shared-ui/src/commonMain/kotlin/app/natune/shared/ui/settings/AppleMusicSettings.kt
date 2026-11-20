package app.natune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.natune.shared.domain.settings.AppleMusicAccountNameKey
import app.natune.shared.domain.settings.AppleMusicDevTokenKey
import app.natune.shared.domain.settings.AppleMusicExperienceKey
import app.natune.shared.domain.settings.AppleMusicMediaUserTokenKey
import app.natune.shared.domain.settings.InterfaceStyle
import app.natune.shared.domain.settings.PreferenceDefaults
import app.natune.shared.domain.settings.applyInterfaceStyle
import app.natune.shared.domain.settings.interfaceStyle
import app.natune.shared.ui.resources.Res
import app.natune.shared.ui.resources.m6_apple_music_account_name
import app.natune.shared.ui.resources.m6_apple_music_dev_token
import app.natune.shared.ui.resources.m6_apple_music_experience
import app.natune.shared.ui.resources.m6_apple_music_sign_in
import app.natune.shared.ui.resources.m6_apple_music_sign_in_desc
import app.natune.shared.ui.resources.m6_apple_music_sign_out
import app.natune.shared.ui.resources.m6_apple_music_tokens_missing
import app.natune.shared.ui.resources.m6_apple_music_tokens_ready
import app.natune.shared.ui.resources.m6_apple_music_user_token
import org.jetbrains.compose.resources.stringResource

const val AppleMusicLoginRoute = "settings/applemusic/login"

/**
 * Settings > Playback > Apple Music on every shared host: the sign-in entry the Android page leads
 * with, the experience toggle and the token fields, with the stored sign-in state on top. A host
 * without a web sign-in passes no [onOpenLogin] and simply shows no sign-in row.
 */
@Composable
fun AppleMusicSettings(
    controller: SettingsController,
    onFailure: () -> Unit,
    scrollTo: String?,
    onOpenLogin: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    val scope = rememberCoroutineScope()
    val signedIn = preferences[AppleMusicMediaUserTokenKey, ""].isNotBlank() &&
        preferences[AppleMusicDevTokenKey, ""].isNotBlank()
    SettingsPage(
        rows = buildList {
            add(
                SettingsRow.Status(
                    "apple_music_tokens_status", Res.string.m6_apple_music_sign_in,
                    value = stringResource(
                        if (signedIn) Res.string.m6_apple_music_tokens_ready
                        else Res.string.m6_apple_music_tokens_missing
                    ),
                ),
            )
            if (onOpenLogin != null) {
                add(
                    SettingsRow.Action(
                        "apple_music_sign_in", Res.string.m6_apple_music_sign_in,
                        description = Res.string.m6_apple_music_sign_in_desc,
                        run = onOpenLogin,
                    ),
                )
            }
            add(
                SettingsRow.Toggle(
                    "apple_music_experience", Res.string.m6_apple_music_experience, AppleMusicExperienceKey,
                    PreferenceDefaults.appleMusicExperience,
                    read = { it.interfaceStyle() == InterfaceStyle.APPLE_MUSIC },
                    write = {
                        applyInterfaceStyle(if (it) InterfaceStyle.APPLE_MUSIC else InterfaceStyle.MATERIAL_EXPRESSIVE)
                    },
                ),
            )
            add(
                SettingsRow.Text(
                    "apple_music_dev_token", Res.string.m6_apple_music_dev_token, AppleMusicDevTokenKey,
                    masked = true,
                ),
            )
            add(
                SettingsRow.Text(
                    "apple_music_user_token", Res.string.m6_apple_music_user_token, AppleMusicMediaUserTokenKey,
                    masked = true,
                ),
            )
            add(
                SettingsRow.Text(
                    "apple_music_account_name", Res.string.m6_apple_music_account_name, AppleMusicAccountNameKey,
                ),
            )
            if (signedIn) {
                add(
                    SettingsRow.Action(
                        "apple_music_sign_out", Res.string.m6_apple_music_sign_out,
                        run = {
                            scope.launch {
                                try {
                                    controller.edit {
                                        this[AppleMusicMediaUserTokenKey] = null
                                        this[AppleMusicDevTokenKey] = null
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    onFailure()
                                }
                            }
                        },
                    ),
                )
            }
        },
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
        modifier = modifier,
    )
}
