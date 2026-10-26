package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A slash-command that the bot advertised for itself. The command and description text come from the bot, so they are
 * shown as given. [command] carries no leading slash.
 */
data class TelegramAdvertisedBotCommand(val command: String, val description: String)

/** A fallback command the picker offers after the advertised ones. The description is a shared resource. */
private class TelegramFallbackBotCommand(val command: String, val description: StringResource)

/**
 * Fallback commands that most music bots accept without a BotFather registration. They are hints only: a bot may read
 * one slightly differently, so the picker never claims the bot supports it.
 */
private val telegramFallbackBotCommands: List<TelegramFallbackBotCommand> = listOf(
    TelegramFallbackBotCommand("start", Res.string.m6_telegram_cmd_start),
    TelegramFallbackBotCommand("help", Res.string.m6_telegram_cmd_help),
    TelegramFallbackBotCommand("search", Res.string.m6_telegram_cmd_search),
    TelegramFallbackBotCommand("download", Res.string.m6_telegram_cmd_download),
    TelegramFallbackBotCommand("song", Res.string.m6_telegram_cmd_song),
    TelegramFallbackBotCommand("music", Res.string.m6_telegram_cmd_music),
    TelegramFallbackBotCommand("lyrics", Res.string.m6_telegram_cmd_lyrics),
    TelegramFallbackBotCommand("flac", Res.string.m6_telegram_cmd_flac),
    TelegramFallbackBotCommand("alac", Res.string.m6_telegram_cmd_alac),
    TelegramFallbackBotCommand("mp3", Res.string.m6_telegram_cmd_mp3),
    TelegramFallbackBotCommand("cancel", Res.string.m6_telegram_cmd_cancel),
)

/**
 * The "/" command picker of a bot chat. Advertised commands come first, then a divider, then the fallback commands.
 * Picking one calls [onInsert] with the text for the composer, "/command " with a trailing space, and then [onDismiss].
 * The host owns the composer text, sending and the bot command fetch, which can fail. An empty [advertised] list
 * shows only the fallbacks and no divider.
 */
@Composable
fun TelegramBotCommandMenu(
    expanded: Boolean,
    advertised: List<TelegramAdvertisedBotCommand>,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        advertised.forEach { command ->
            TelegramBotCommandRow(
                command = command.command,
                description = command.description,
                onPick = {
                    onInsert("/${command.command} ")
                    onDismiss()
                },
            )
        }
        if (advertised.isNotEmpty()) {
            HorizontalDivider()
        }
        telegramFallbackBotCommands.forEach { command ->
            TelegramBotCommandRow(
                command = command.command,
                description = stringResource(command.description),
                onPick = {
                    onInsert("/${command.command} ")
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun TelegramBotCommandRow(command: String, description: String, onPick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = "/$command",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = onPick,
    )
}
