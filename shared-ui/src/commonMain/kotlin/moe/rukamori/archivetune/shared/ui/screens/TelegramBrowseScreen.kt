package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.BusyCircularProgress
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Owner-produced outcome of the last search or open. The screen shows it until dismissed. */
sealed interface TelegramBrowseNotice {
    data class SearchFailed(val detail: String) : TelegramBrowseNotice
    data object OpenFailed : TelegramBrowseNotice
}

/** [membersText] is the host-formatted, locale-aware member count; null when the count is unknown. */
data class TelegramChannelUi(
    val chatId: Long,
    val title: String,
    val username: String?,
    val broadcast: Boolean,
    val membersText: String?,
)

data class TelegramBrowseUi(
    val query: String,
    val searching: Boolean,
    val searched: Boolean,
    val opening: Boolean,
    val results: List<TelegramChannelUi>,
    val notice: TelegramBrowseNotice?,
)

/**
 * [search] receives the trimmed query. [open] materialises the channel as a local playlist and passes its id
 * to the callback once it exists; the owner must cancel pending work when the entry leaves composition.
 */
data class TelegramBrowseActions(
    val back: () -> Unit,
    val query: (String) -> Unit,
    val search: (String) -> Unit,
    val open: (TelegramChannelUi, (String) -> Unit) -> Unit,
    val dismissNotice: () -> Unit,
)

@Composable
fun TelegramBrowseScreen(
    state: TelegramBrowseUi,
    actions: TelegramBrowseActions,
    avatar: @Composable (TelegramChannelUi, Modifier) -> Unit,
    onPlaylistOpened: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    Column(modifier.fillMaxSize()) {
        ManagementHeader(stringResource(Res.string.m4_telegram_browse_title), actions.back)
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.query,
            placeholder = { Text(stringResource(Res.string.m4_telegram_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (!state.searching && state.query.isNotBlank()) actions.search(state.query.trim())
            }),
            trailingIcon = {
                TextButton(onClick = { actions.search(state.query.trim()) },
                    enabled = !state.searching && state.query.isNotBlank(),
                    modifier = Modifier.testTag("telegram-browse-search")) {
                    Text(stringResource(Res.string.m4_search))
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("telegram-browse-query"),
        )
        state.notice?.let { notice ->
            val message = noticeText(notice)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(actions.dismissNotice) { Text(stringResource(Res.string.m4_telegram_dismiss)) }
            }
        }
        // Searching and empty states take the remaining height so the layout stays stable while results arrive.
        when {
            state.searching -> Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                BusyCircularProgress()
            }
            state.searched && state.results.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.m4_telegram_no_results))
            }
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = contentPadding) {
                items(state.results, key = { it.chatId }) { channel ->
                    TelegramChannelRow(channel, avatar, enabled = !state.opening,
                        onClick = { actions.open(channel, onPlaylistOpened) })
                }
            }
        }
    }
}

@Composable
private fun TelegramChannelRow(
    channel: TelegramChannelUi,
    avatar: @Composable (TelegramChannelUi, Modifier) -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val kind = stringResource(if (channel.broadcast) Res.string.m4_telegram_channel else Res.string.m4_telegram_group)
    val members = channel.membersText?.let { stringResource(Res.string.m4_telegram_members, it) }
    val details = listOfNotNull(channel.username?.let { "@$it" }, kind, members).joinToString(" • ")
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp).testTag("telegram-channel-${channel.chatId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar(channel, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun noticeText(notice: TelegramBrowseNotice): String = when (notice) {
    is TelegramBrowseNotice.SearchFailed -> stringResource(Res.string.m4_telegram_error, notice.detail)
    TelegramBrowseNotice.OpenFailed -> stringResource(Res.string.m4_telegram_open_failed)
}
