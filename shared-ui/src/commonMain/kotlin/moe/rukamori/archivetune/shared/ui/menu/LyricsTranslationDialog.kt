package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.settings.translatorLanguageOptions
import org.jetbrains.compose.resources.stringResource

/** Android's Source and Language dialog; the Java-only ordinary translator remains visible and unavailable on iOS. */
@Composable
fun LyricsTranslationDialog(initialLanguage: String, canTranslate: Boolean, translate: suspend (String) -> Boolean,
    onDismiss: () -> Unit, onFailure: () -> Unit) {
    var language by remember { mutableStateOf(initialLanguage) }
    var languages by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val options = translatorLanguageOptions()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(Res.string.m6_translate_only)) },
        text = { Column {
            Text(stringResource(Res.string.m12_playback_error_source))
            Box {
                TextButton(onClick = { sources = true }) { Text(stringResource(Res.string.phone_lyrics_ai_translation_menu)) }
                DropdownMenu(sources, onDismissRequest = { sources = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.phone_lyrics_ai_translation_menu)) }, onClick = { sources = false })
                    DropdownMenuItem(enabled = false, onClick = {}, text = { Column {
                        Text(stringResource(Res.string.m6_translate_only))
                        Text(stringResource(Res.string.phone_not_available_on_ios), style = MaterialTheme.typography.bodySmall)
                    } })
                }
            }
            Text(stringResource(Res.string.phone_lyrics_language_label))
            Box {
                TextButton(onClick = { languages = true }) { Text(stringResource(options.firstOrNull { it.value == language }?.label ?: Res.string.m6_language_english)) }
                DropdownMenu(languages, onDismissRequest = { languages = false }) {
                    options.forEach { option -> DropdownMenuItem(text = { Text(stringResource(option.label)) }, onClick = { language = option.value; languages = false }) }
                }
            }
        } },
        confirmButton = { TextButton(enabled = canTranslate && !busy, onClick = {
            busy = true
            scope.launch {
                try { if (translate(language)) onDismiss() else onFailure() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
                finally { busy = false }
            }
        }) { Text(stringResource(Res.string.m6_translate_only)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) } })
}
