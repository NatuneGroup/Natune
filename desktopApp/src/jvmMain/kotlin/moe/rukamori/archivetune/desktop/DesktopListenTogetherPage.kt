package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.ListenTogetherActions
import moe.rukamori.archivetune.shared.ui.settings.ListenTogetherSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice

/** The Listen Together settings route, as the shared settings index names it. */
internal const val DesktopListenTogetherSettingsRoute = "settings/integrations/listen_together"

/**
 * The Listen Together room page over the graph's one owner. The room chat, the song picker for suggestions and the
 * custom member picture are not connected on this host, so those actions say so on the page instead of opening nothing.
 */
@Composable
internal fun desktopListenTogetherPage(graph: DesktopSessionGraph, openRoute: (String) -> Unit): ArchiveGraphPage {
    val owner = graph.listenTogether
    val state by owner.ui.collectAsState()
    val actions = remember(owner) {
        val unavailable = { owner.showMessage(DesktopStrings["settings_feature_unavailable"]) }
        ListenTogetherActions(
            back = { },
            connect = owner::connect,
            disconnect = owner::disconnect,
            reconnect = owner::reconnect,
            username = owner::setUsername,
            roomCode = owner::setRoomCode,
            create = owner::create,
            join = owner::join,
            cancelJoin = owner::cancelJoin,
            leave = owner::leave,
            copyCode = { owner.client.roomCode.value?.let(::copyToDesktopClipboard) },
            shareInvite = { owner.inviteLink()?.let(::copyToDesktopClipboard) },
            chat = unavailable,
            approve = owner::approve,
            reject = owner::reject,
            approveSuggestion = owner::approveSuggestion,
            rejectSuggestion = owner::rejectSuggestion,
            kick = owner::kick,
            transfer = owner::transfer,
            suggestSong = unavailable,
            settings = { openRoute(DesktopListenTogetherSettingsRoute) },
        )
    }
    return ArchiveGraphPage.ListenTogether(state, actions, avatar = { member, modifier ->
        DesktopListenTogetherAvatar(member.name, modifier)
    })
}

/** Settings > Listen Together over the same owner, so a blocked user released here is released in the live client too. */
@Composable
internal fun DesktopListenTogetherSettingsPage(
    graph: DesktopSessionGraph,
    controller: SettingsController,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    unavailable: () -> Unit,
    scrollTo: String?,
) {
    val owner = graph.listenTogether
    val role by owner.settingsRole.collectAsState()
    val logs by owner.logs.collectAsState()
    ListenTogetherSettings(
        controller = controller,
        servers = owner.servers,
        role = role,
        logs = logs,
        onPickCustomAvatar = unavailable,
        onUnblockUser = owner.client::unblockUser,
        onClearLogs = owner::clearLogs,
        onCopyLogs = ::copyToDesktopClipboard,
        onNotice = onNotice,
        onFailure = onFailure,
        scrollTo = scrollTo,
        avatar = { index, modifier -> DesktopListenTogetherAvatar("${index + 1}", modifier) },
    )
}

/** A member's mark: the first letter of the name on a round tile. The preset avatar art is not bundled on this host. */
@Composable
private fun DesktopListenTogetherAvatar(label: String, modifier: Modifier) {
    Box(
        modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.trim().take(if (label.trim().all(Char::isDigit)) 2 else 1).uppercase(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
