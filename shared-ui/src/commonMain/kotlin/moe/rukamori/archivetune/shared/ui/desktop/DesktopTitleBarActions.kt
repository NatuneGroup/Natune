package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.component.ProfileMenuHost
import moe.rukamori.archivetune.shared.ui.component.ProfileMenuState
import moe.rukamori.archivetune.shared.ui.component.profileMenuItems
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The account area of the desktop title bar: the shared profile menu's model and navigation, plus
 * the account picture. The picture is a host callback because only the host owns an image loader; a
 * null [avatar] or a blank [imageUrl] draws the account icon instead.
 */
class DesktopProfileMenu(
    val state: ProfileMenuState,
    val host: ProfileMenuHost,
    val imageUrl: String? = null,
    val avatar: (@Composable (uri: String, modifier: Modifier) -> Unit)? = null,
)

/**
 * The settings and account buttons at the right of the title bar, before the host's window buttons.
 * The account button carries the 28 dp avatar of the Android top bar inside a 40 dp touch target,
 * and its menu is the shared profile menu's rows in a dropdown anchored to it, the shape the
 * reference desktop app uses, so the desktop never opens a second window for a menu. The settings
 * button is the title bar's own way to Settings, so the menu leaves that row out.
 */
@Composable
fun DesktopTitleBarActions(
    settingsBadge: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    profile: DesktopProfileMenu? = null,
    modifier: Modifier = Modifier,
) {
    if (onOpenSettings == null && profile == null) return
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (onOpenSettings != null) DesktopSettingsButton(settingsBadge, onOpenSettings)
        if (profile != null) {
            Box {
                DesktopAccountButton(profile, active = menuOpen, onClick = { menuOpen = true })
                DesktopProfileMenuList(
                    profile,
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                )
            }
        }
    }
}

/**
 * The settings glyph in a 40 dp circle, with the update dot the phone top bar shows when a newer
 * build is offered.
 */
@Composable
private fun DesktopSettingsButton(badge: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.size(DesktopShellMetrics.titleBarActionButton)) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    )
                    .clickable(role = Role.Button, onClick = onClick)
                    .testTag("desktop-settings-button"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.m6_icon_settings),
                contentDescription = stringResource(Res.string.m6_profile_settings),
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (badge) {
            Box(
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                        .testTag("desktop-settings-badge"),
            )
        }
    }
}

/** The account avatar button: a 28 dp circle on `primaryContainer`, like the phone top bar's. */
@Composable
private fun DesktopAccountButton(
    profile: DesktopProfileMenu,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(DesktopShellMetrics.titleBarActionButton)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("desktop-account-button"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(DesktopShellMetrics.titleBarAvatar),
            shape = CircleShape,
            color =
                if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
        ) {
            val avatar = profile.avatar
            val image = profile.imageUrl
            if (avatar != null && !image.isNullOrBlank()) {
                Box(Modifier.fillMaxSize().clip(CircleShape)) {
                    avatar(image, Modifier.fillMaxSize())
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(Res.drawable.m6_icon_account),
                        contentDescription = stringResource(Res.string.m6_profile_account),
                        modifier = Modifier.size(20.dp),
                        tint =
                            if (active) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                    )
                }
            }
        }
    }
}

/**
 * The shared profile menu's rows, in the shared order and with the shared badges, anchored under
 * the avatar.
 */
@Composable
private fun DesktopProfileMenuList(
    profile: DesktopProfileMenu,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 220.dp).testTag("desktop-account-menu"),
    ) {
        // The account row the dialog draws as its header, so a signed-in listener sees whose menu
        // this is.
        val accountName = profile.state.accountName
        if (!accountName.isNullOrBlank()) {
            Column(
                modifier =
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("desktop-account-menu-name"),
            ) {
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
            HorizontalDivider()
        }
        profileMenuItems(state = profile.state, host = profile.host, onDismiss = onDismiss)
            .forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.label)) },
                    leadingIcon = {
                        BadgedBox(badge = { if (item.showBadge) Badge() }) {
                            Icon(
                                painter = painterResource(item.icon),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    onClick = item.onClick,
                    modifier = Modifier.testTag("desktop-account-menu-${item.id}"),
                )
            }
    }
}

/**
 * The routes the account menu's rows open, through the window's one navigation owner. The routes
 * are the ones the desktop keymap already uses, so a menu row and its shortcut land on the same
 * page. Every value here has to be a route [ArchiveRoutes.resolve] accepts and this host mounts, so
 * the Last.fm dashboard row opens [ArchiveRoutes.LASTFM_DASHBOARD_ROUTE]: the settings route the
 * desktop host mounts, not the phone's own dashboard destination.
 */
fun desktopProfileMenuHost(navigation: ShellNavigation): ProfileMenuHost =
    object : ProfileMenuHost {
        override fun openHistory() {
            navigation.open(HISTORY_ROUTE)
        }

        override fun openNews() {
            navigation.open("news")
        }

        override fun openNewReleases() {
            navigation.open("new_release")
        }

        override fun openLastFmDashboard() {
            navigation.open(ArchiveRoutes.LASTFM_DASHBOARD_ROUTE)
        }

        override fun openMusicRecognition() {
            navigation.open(ArchiveRoutes.musicRecognition())
        }

        override fun openListenTogether() {
            navigation.open("listen_together_from_topbar")
        }

        override fun openSettings() {
            navigation.open("settings")
        }
    }
