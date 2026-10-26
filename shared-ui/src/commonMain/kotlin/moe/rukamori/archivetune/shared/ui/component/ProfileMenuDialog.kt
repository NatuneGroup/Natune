package moe.rukamori.archivetune.shared.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the profile menu shows. The host decides each gate from its own settings and route; this model only carries the
 * result, so the dialog never reads a preference or a navigation state.
 */
@Immutable
data class ProfileMenuState(
    /** The signed-in account name, shown under the header when [headerTitle] is null. Blank hides the account row. */
    val accountName: String?,
    /** When set, it replaces the account row, so the same dialog can serve a menu that is not about the account. */
    val headerTitle: StringResource? = null,
    /** Shows the unread badge on the News row. */
    val hasUnreadNews: Boolean = false,
    /** Shows the badge on the Settings row. */
    val showSettingsBadge: Boolean = false,
    /** Listen Together is listed only when the user has pinned it to the top bar. */
    val showListenTogether: Boolean,
    /** Settings is listed everywhere except the Home route, which has its own settings button. */
    val showSettings: Boolean,
)

/**
 * Navigation requested by the profile rows. The dialog dismisses itself before each call, as the baseline does, so the
 * host only has to navigate.
 */
interface ProfileMenuHost {
    fun openHistory()

    fun openNews()

    fun openNewReleases()

    fun openLastFmDashboard()

    fun openMusicRecognition()

    fun openListenTogether()

    fun openSettings()
}

/** One profile row: its icon, its label, whether it carries the unread dot, and what a tap does. */
data class ProfileMenuItem(
    val icon: DrawableResource,
    val label: StringResource,
    val showBadge: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Builds the profile rows in the baseline order. Each row dismisses the dialog and then calls the matching host method.
 * Listen Together and Settings appear only when [ProfileMenuState] allows them.
 */
fun profileMenuItems(
    state: ProfileMenuState,
    host: ProfileMenuHost,
    onDismiss: () -> Unit,
): List<ProfileMenuItem> =
    buildList {
        add(
            ProfileMenuItem(
                icon = Res.drawable.m6_icon_history,
                label = Res.string.m6_profile_history,
                onClick = {
                    onDismiss()
                    host.openHistory()
                },
            ),
        )
        add(
            ProfileMenuItem(
                icon = Res.drawable.m6_icon_newspaper,
                label = Res.string.m6_profile_news,
                showBadge = state.hasUnreadNews,
                onClick = {
                    onDismiss()
                    host.openNews()
                },
            ),
        )
        add(
            ProfileMenuItem(
                icon = Res.drawable.m6_icon_new_release,
                label = Res.string.m6_profile_new_release_albums,
                onClick = {
                    onDismiss()
                    host.openNewReleases()
                },
            ),
        )
        add(
            ProfileMenuItem(
                icon = Res.drawable.m6_icon_stats,
                label = Res.string.m6_profile_lastfm_dashboard,
                onClick = {
                    onDismiss()
                    host.openLastFmDashboard()
                },
            ),
        )
        add(
            ProfileMenuItem(
                icon = Res.drawable.m6_icon_mic,
                label = Res.string.m6_profile_music_recognition,
                onClick = {
                    onDismiss()
                    host.openMusicRecognition()
                },
            ),
        )
        if (state.showListenTogether) {
            add(
                ProfileMenuItem(
                    icon = Res.drawable.m6_icon_listen_together,
                    label = Res.string.m6_profile_listen_together,
                    onClick = {
                        onDismiss()
                        host.openListenTogether()
                    },
                ),
            )
        }
        if (state.showSettings) {
            add(
                ProfileMenuItem(
                    icon = Res.drawable.m6_icon_settings,
                    label = Res.string.m6_profile_settings,
                    showBadge = state.showSettingsBadge,
                    onClick = {
                        onDismiss()
                        host.openSettings()
                    },
                ),
            )
        }
    }

/**
 * The profile menu as a dialog over a scrim. [accountAvatar] draws the account picture inside a 40 dp circle, so the
 * host keeps its image loader; when it is null the account icon is shown. The dialog closes from the close button, the
 * scrim, or the system back gesture.
 */
@Composable
fun ProfileMenuDialog(
    state: ProfileMenuState,
    host: ProfileMenuHost,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accountAvatar: (@Composable () -> Unit)? = null,
) {
    val items = profileMenuItems(state = state, host = host, onDismiss = onDismiss)
    val accountName = state.accountName
    val headerTitle = state.headerTitle
    val hasHeader = headerTitle != null || !accountName.isNullOrBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = scrimInteraction,
                            indication = null,
                        ) { onDismiss() },
            )

            Surface(
                modifier =
                    Modifier
                        .widthIn(max = 360.dp)
                        .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (headerTitle != null) {
                                Text(
                                    text = stringResource(headerTitle),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            } else if (!accountName.isNullOrBlank()) {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    if (accountAvatar != null) {
                                        Box(modifier = Modifier.fillMaxSize().clip(CircleShape)) {
                                            accountAvatar()
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(Res.drawable.m6_icon_account),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = accountName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(Res.string.m6_profile_account),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Box(
                            modifier =
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.m6_icon_close),
                                contentDescription = stringResource(Res.string.m6_profile_close),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (hasHeader) {
                        Spacer(Modifier.height(8.dp))
                    }

                    items.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                                    .clickable { item.onClick() }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            BadgedBox(badge = {
                                if (item.showBadge) Badge()
                            }) {
                                Icon(
                                    painter = painterResource(item.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = stringResource(item.label),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}
