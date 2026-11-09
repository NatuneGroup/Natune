package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Android SearchEntryField: rounded tonal field, inline microphone and source picker. */
@Composable
internal fun PhoneSearchField(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    scope: SearchScope,
    provider: ContentProvider,
    onScope: (SearchScope) -> Unit,
    onProvider: ((ContentProvider) -> Unit)?,
    onVoice: (() -> Unit)?,
    onRecognition: (() -> Unit)?,
    modifier: Modifier,
) {
    var sources by remember { mutableStateOf(false) }
    Row(modifier.heightIn(min = 58.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp)),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(Res.drawable.m4_icon_search), null, Modifier.padding(start = 16.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(value, onValue, singleLine = true, textStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp), decorationBox = { field ->
                Box {
                    if (value.text.isEmpty()) Text(stringResource(if (scope == SearchScope.Local) Res.string.phone_search_library else Res.string.phone_search_online),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp))
                    field()
                }
            })
        IconButton(onClick = { onVoice?.invoke() }, enabled = onVoice != null) {
            Icon(painterResource(Res.drawable.m4_icon_mic), stringResource(Res.string.m4_voice_search), Modifier.size(22.dp))
        }
        onRecognition?.let { recognize -> IconButton(onClick = recognize) {
            Icon(painterResource(Res.drawable.m4_icon_listening), stringResource(Res.string.m4_recognize), Modifier.size(22.dp))
        } }
        Box {
            IconButton(onClick = { sources = true }) {
                Icon(painterResource(when {
                    scope == SearchScope.Local -> Res.drawable.m4_icon_library_outlined
                    provider == ContentProvider.SPOTIFY_CATALOG -> Res.drawable.m9_spotify_icon
                    else -> Res.drawable.m4_icon_music_note
                }), stringResource(Res.string.phone_search_source), Modifier.size(22.dp))
            }
            DropdownMenu(sources, onDismissRequest = { sources = false }) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.m4_local)) }, onClick = { sources = false; onScope(SearchScope.Local) })
                listOf(ContentProvider.YOUTUBE to Res.string.m4_youtube, ContentProvider.SPOTIFY_CATALOG to Res.string.m4_spotify,
                    ContentProvider.APPLE to Res.string.m6_apple_music).forEach { (choice, title) ->
                    DropdownMenuItem(text = { Text(stringResource(title)) }, enabled = onProvider != null, onClick = {
                        sources = false; onScope(SearchScope.Online); onProvider?.invoke(choice)
                    })
                }
            }
        }
    }
}
