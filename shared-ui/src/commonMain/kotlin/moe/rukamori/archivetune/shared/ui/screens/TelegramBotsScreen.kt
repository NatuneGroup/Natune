package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.BusyCircularProgress
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.setValue

/** Outcome of the last add attempt, produced by the Telegram owner. Null means nothing is pending. */
sealed interface TelegramBotNotice {
    data object InvalidUsername : TelegramBotNotice
    data class AlreadyAdded(val username: String) : TelegramBotNotice
    data object ResolveFailed : TelegramBotNotice
}

/** The handle is derived from [username]; a blank [title] falls back to the handle. */
data class TelegramBotUi(val id: String, val username: String, val title: String)

data class TelegramBotsUi(
    /** False until the Telegram account is signed in. Bots can then neither be added nor opened. */
    val ready: Boolean,
    val input: String,
    val adding: Boolean,
    val bots: List<TelegramBotUi>,
    val forwardToChannel: Boolean,
    val notice: TelegramBotNotice?,
    /**
     * True when the saved list could not be read. The list and the forward choice are unknown then, so the screen
     * shows the failure with a retry instead of an empty list, and editing waits for a successful read.
     */
    val loadFailed: Boolean,
)

/**
 * [add] receives the pasted text; the owner parses it, resolves the bot, persists the list and reports
 * the outcome through [TelegramBotsUi.notice]. Nothing here fakes a successful add. [retry] reads the saved
 * list again after a failed read.
 */
data class TelegramBotsActions(
    val back: () -> Unit,
    val input: (String) -> Unit,
    val add: (String) -> Unit,
    val open: (TelegramBotUi) -> Unit,
    val remove: (TelegramBotUi) -> Unit,
    val forwardToChannel: (Boolean) -> Unit,
    val dismissNotice: () -> Unit,
    val retry: () -> Unit,
)

@Composable
fun TelegramBotsScreen(
    state: TelegramBotsUi,
    actions: TelegramBotsActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    var pendingRemoveId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        ManagementHeader(stringResource(Res.string.m4_telegram_bots_title), actions.back)
        OutlinedTextField(
            value = state.input,
            onValueChange = actions.input,
            placeholder = { Text(stringResource(Res.string.m4_telegram_bots_hint)) },
            singleLine = true,
            // Editing waits for a successful read: a save made from a list that was never read would overwrite it.
            enabled = state.ready && !state.adding && !state.loadFailed,
            trailingIcon = {
                if (state.adding) {
                    BusyCircularProgress(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { actions.add(state.input) },
                        enabled = state.ready && !state.loadFailed && state.input.isNotBlank(),
                        modifier = Modifier.testTag("telegram-bot-add")) {
                        Text(stringResource(Res.string.m4_telegram_bots_add))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("telegram-bot-input"),
        )
        state.notice?.let { notice ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(noticeText(notice), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(actions.dismissNotice) { Text(stringResource(Res.string.m4_telegram_dismiss)) }
            }
        }
        // Each branch takes the remaining height so the forward toggle keeps a stable position.
        when {
            state.loadFailed -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                LoadFeedback(loading = false, failed = true, empty = false, retry = actions.retry)
            }
            !state.ready -> Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.m4_telegram_login_required))
            }
            state.bots.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.m4_telegram_bots_empty))
            }
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = contentPadding) {
                items(state.bots, key = { it.id }) { bot ->
                    TelegramBotRow(bot, onOpen = { actions.open(bot) }, onRemove = { pendingRemoveId = bot.id })
                }
            }
        }
        // The forward choice is unknown until the saved list has been read, so no switch is shown after a failed read.
        if (!state.loadFailed) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.m4_telegram_forward_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(Res.string.m4_telegram_forward_summary), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(state.forwardToChannel, actions.forwardToChannel, modifier = Modifier.testTag("telegram-bot-forward"))
        }
    }
    val removing = state.bots.firstOrNull { it.id == pendingRemoveId }
    if (removing != null) AlertDialog(
        onDismissRequest = { pendingRemoveId = null },
        title = { Text(stringResource(Res.string.m4_telegram_bots_remove)) },
        text = { Text(stringResource(Res.string.m4_telegram_bots_remove_confirm, removing.username)) },
        confirmButton = {
            TextButton({ pendingRemoveId = null; actions.remove(removing) }) { Text(stringResource(Res.string.m4_telegram_ok)) }
        },
        dismissButton = { TextButton({ pendingRemoveId = null }) { Text(stringResource(Res.string.m4_cancel)) } },
    )
}

@Composable
private fun TelegramBotRow(bot: TelegramBotUi, onOpen: () -> Unit, onRemove: () -> Unit) {
    val handle = "@${bot.username}"
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp).testTag("telegram-bot-${bot.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(bot.title.ifBlank { handle }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(handle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onRemove, modifier = Modifier.testTag("telegram-bot-remove-${bot.id}")) {
            Text(stringResource(Res.string.m4_telegram_bots_remove))
        }
    }
}

@Composable
private fun noticeText(notice: TelegramBotNotice): String = when (notice) {
    TelegramBotNotice.InvalidUsername -> stringResource(Res.string.m4_telegram_bots_invalid)
    is TelegramBotNotice.AlreadyAdded -> stringResource(Res.string.m4_telegram_bots_already_added, notice.username)
    TelegramBotNotice.ResolveFailed -> stringResource(Res.string.m4_telegram_bots_resolve_failed)
}
