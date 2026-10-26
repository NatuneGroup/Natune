package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.BusyCircularProgress
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * One chip of a bot's inline keyboard. [index] is the position in the prompt's flattened button list, which the
 * owner uses to find the button it must click. [url] is set for link buttons; they open through
 * [TelegramBotChatActions.openLink] and are never sent to the bot as callbacks.
 */
data class TelegramBotPromptButtonUi(val index: Int, val text: String, val url: String?)

/** A bot's inline keyboard. Rows keep the bot's grouping; [text] is shown above the chips when not blank. */
data class TelegramBotPromptUi(val text: String, val rows: List<List<TelegramBotPromptButtonUi>>)

/** A slash command without its leading slash. Advertised commands come from the owner; fallbacks are shown here. */
data class TelegramBotCommandUi(val command: String, val description: String)

/** One audio reply from the bot. [id] is the owner's stable media id; [performer] is already resolved by the owner. */
data class TelegramBotTrackUi(val id: String, val title: String, val performer: String?)

/**
 * Owner-produced outcome of the last send, choice, download or forward. Success is only shown when the owner
 * reports [Forwarded]; the screen never infers it.
 */
sealed interface TelegramBotChatNotice {
    data object ResolveFailed : TelegramBotChatNotice
    data class SendFailed(val detail: String) : TelegramBotChatNotice
    data object DownloadFailed : TelegramBotChatNotice
    data object Forwarded : TelegramBotChatNotice
    data class ForwardFailed(val detail: String) : TelegramBotChatNotice
}

/**
 * [botUsername] is null when the bot is no longer saved; the screen then offers only a way back. [pendingChoice]
 * is the text of the chip whose callback is in flight. [noReply] means the owner's collection window ended with
 * no audio and no prompt.
 */
data class TelegramBotChatUi(
    val botUsername: String?,
    val input: String,
    val sending: Boolean,
    val pendingChoice: String?,
    val prompt: TelegramBotPromptUi?,
    val noReply: Boolean,
    val results: List<TelegramBotTrackUi>,
    val advertisedCommands: List<TelegramBotCommandUi>,
    val notice: TelegramBotChatNotice?,
)

/**
 * [send] receives the raw input; the owner trims it and ignores blank or in-flight requests. [choose] runs only
 * for callback buttons. The owner resolves the bot chat, persists tracks, plays, downloads and forwards; this
 * screen only reports intent. [back] is rebound by the host.
 */
data class TelegramBotChatActions(
    val back: () -> Unit,
    val input: (String) -> Unit,
    val send: (String) -> Unit,
    val choose: (TelegramBotPromptButtonUi) -> Unit,
    val openLink: (String) -> Unit,
    val play: (TelegramBotTrackUi) -> Unit,
    val playAll: () -> Unit,
    val download: (TelegramBotTrackUi) -> Unit,
    val addToPlaylist: (TelegramBotTrackUi) -> Unit,
    val dismissNotice: () -> Unit,
)

/**
 * One-on-one chat with a saved Telegram bot. [artwork] is host-supplied and receives each audio reply, so the
 * artwork decoder stays outside this screen.
 */
@Composable
fun TelegramBotChatScreen(
    state: TelegramBotChatUi,
    actions: TelegramBotChatActions,
    artwork: @Composable (TelegramBotTrackUi, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val username = state.botUsername
    if (username == null) {
        // The bot was removed from another device; the only honest action is to go back.
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            TextButton(actions.back) { Text(stringResource(Res.string.m4_telegram_bot_missing)) }
        }
        return
    }
    Column(modifier.fillMaxSize()) {
        ManagementHeader(stringResource(Res.string.m4_telegram_bot_chat_title, username), actions.back)
        ChatInputRow(state, actions)
        state.notice?.let { notice ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chatNoticeText(notice),
                    Modifier.weight(1f),
                    color = if (notice == TelegramBotChatNotice.Forwarded) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                TextButton(actions.dismissNotice) { Text(stringResource(Res.string.m4_telegram_dismiss)) }
            }
        }
        if (state.sending && state.prompt == null && state.results.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BusyCircularProgress()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(Res.string.m4_telegram_bot_waiting))
                }
            }
        }
        // The quality or option keyboard stays above the results so the user chooses before audio arrives.
        state.prompt?.let { prompt ->
            ChatPromptCard(
                prompt = prompt,
                pendingChoice = state.pendingChoice,
                onChoose = { button ->
                    val link = button.url
                    if (link != null) actions.openLink(link) else actions.choose(button)
                },
            )
        }
        if (state.noReply) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(Res.string.m4_telegram_bot_no_reply),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.results.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.m4_telegram_bot_results, state.results.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                if (state.results.size > 1) {
                    TextButton(actions.playAll) { Text(stringResource(Res.string.m4_telegram_bot_play_all)) }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = contentPadding) {
                items(state.results, key = { it.id }) { track ->
                    TelegramBotTrackRow(
                        track = track,
                        artwork = artwork,
                        onPlay = { actions.play(track) },
                        onDownload = { actions.download(track) },
                        onAddToPlaylist = { actions.addToPlaylist(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputRow(state: TelegramBotChatUi, actions: TelegramBotChatActions) {
    var commandMenuOpen by remember { mutableStateOf(false) }
    val fallbackCommands = fallbackTelegramBotCommands()
    OutlinedTextField(
        value = state.input,
        onValueChange = actions.input,
        placeholder = { Text(stringResource(Res.string.m4_telegram_bot_chat_hint)) },
        singleLine = true,
        enabled = !state.sending,
        leadingIcon = {
            Box {
                TextButton(onClick = { commandMenuOpen = true }, enabled = !state.sending) {
                    Text("/", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = commandMenuOpen, onDismissRequest = { commandMenuOpen = false }) {
                    // The bot's registered commands come first so its own set is visible at a glance.
                    state.advertisedCommands.forEach { command ->
                        TelegramBotCommandItem(command) {
                            actions.input("/${command.command} ")
                            commandMenuOpen = false
                        }
                    }
                    if (state.advertisedCommands.isNotEmpty()) HorizontalDivider()
                    // Fallbacks that most music bots accept even without a BotFather registration.
                    fallbackCommands.forEach { command ->
                        TelegramBotCommandItem(command) {
                            actions.input("/${command.command} ")
                            commandMenuOpen = false
                        }
                    }
                }
            }
        },
        trailingIcon = {
            TextButton(onClick = { actions.send(state.input) }, enabled = !state.sending && state.input.isNotBlank()) {
                if (state.sending && state.pendingChoice == null) {
                    BusyCircularProgress(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(Res.string.m4_telegram_bot_send))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("telegram-bot-chat-input"),
    )
}

@Composable
private fun TelegramBotCommandItem(command: TelegramBotCommandUi, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    "/${command.command}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (command.description.isNotBlank()) {
                    Text(
                        command.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun fallbackTelegramBotCommands(): List<TelegramBotCommandUi> = listOf(
    TelegramBotCommandUi("start", stringResource(Res.string.m4_telegram_cmd_start)),
    TelegramBotCommandUi("help", stringResource(Res.string.m4_telegram_cmd_help)),
    TelegramBotCommandUi("search", stringResource(Res.string.m4_telegram_cmd_search)),
    TelegramBotCommandUi("download", stringResource(Res.string.m4_telegram_cmd_download)),
    TelegramBotCommandUi("song", stringResource(Res.string.m4_telegram_cmd_song)),
    TelegramBotCommandUi("music", stringResource(Res.string.m4_telegram_cmd_music)),
    TelegramBotCommandUi("lyrics", stringResource(Res.string.m4_telegram_cmd_lyrics)),
    TelegramBotCommandUi("flac", stringResource(Res.string.m4_telegram_cmd_flac)),
    TelegramBotCommandUi("alac", stringResource(Res.string.m4_telegram_cmd_alac)),
    TelegramBotCommandUi("mp3", stringResource(Res.string.m4_telegram_cmd_mp3)),
    TelegramBotCommandUi("cancel", stringResource(Res.string.m4_telegram_cmd_cancel)),
)

/**
 * Renders an inline keyboard as a card: optional text, then each keyboard row as its own [FlowRow] so grouping
 * survives. While a callback is in flight its chip shows a spinner and every chip is disabled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatPromptCard(
    prompt: TelegramBotPromptUi,
    pendingChoice: String?,
    onChoose: (TelegramBotPromptButtonUi) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        if (prompt.text.isNotBlank()) {
            Text(
                text = prompt.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        prompt.rows.forEach { row ->
            FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { button ->
                    val isPending = pendingChoice == button.text
                    FilterChip(
                        selected = false,
                        onClick = { onChoose(button) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPending) {
                                    BusyCircularProgress(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(button.text)
                            }
                        },
                        enabled = pendingChoice == null,
                    )
                }
            }
        }
    }
}

@Composable
private fun TelegramBotTrackRow(
    track: TelegramBotTrackUi,
    artwork: @Composable (TelegramBotTrackUi, Modifier) -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("telegram-bot-track-${track.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            // The surface color is the placeholder; the host artwork covers it once it loads.
            artwork(track, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.performer?.takeIf { it.isNotBlank() }?.let { performer ->
                Text(
                    text = performer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onPlay) { Text(stringResource(Res.string.m4_telegram_bot_stream)) }
        TextButton(onDownload) { Text(stringResource(Res.string.m4_telegram_bot_download)) }
        TextButton(onAddToPlaylist) { Text(stringResource(Res.string.m4_telegram_bot_add_to_playlist)) }
    }
}

@Composable
private fun chatNoticeText(notice: TelegramBotChatNotice): String = when (notice) {
    TelegramBotChatNotice.ResolveFailed -> stringResource(Res.string.m4_telegram_bots_resolve_failed)
    is TelegramBotChatNotice.SendFailed -> stringResource(Res.string.m4_telegram_error, notice.detail)
    TelegramBotChatNotice.DownloadFailed -> stringResource(Res.string.m4_telegram_open_failed)
    TelegramBotChatNotice.Forwarded -> stringResource(Res.string.m4_telegram_bot_forwarded)
    is TelegramBotChatNotice.ForwardFailed -> stringResource(Res.string.m4_telegram_bot_forward_failed, notice.detail)
}
