package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import org.jetbrains.compose.resources.stringResource

enum class SearchScope { Online, Local }

@Composable
fun SearchScreen(
    query: String,
    scope: SearchScope,
    onQuery: (String) -> Unit,
    onScope: (SearchScope) -> Unit,
    onSubmit: () -> Unit,
    onVoice: (() -> Unit)?,
    onRecognition: (() -> Unit)?,
    state: PagedContentState,
    actions: ContentActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Text(stringResource(Res.string.m4_search), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(16.dp))
        OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .testTag("search-query"), singleLine = true,
            label = { Text(stringResource(Res.string.m4_search_query)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSubmit() }))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(scope == SearchScope.Online, { onScope(SearchScope.Online) },
                label = { Text(stringResource(Res.string.m4_online)) })
            FilterChip(scope == SearchScope.Local, { onScope(SearchScope.Local) },
                label = { Text(stringResource(Res.string.m4_local)) })
            TextButton(onSubmit, enabled = query.isNotBlank(), modifier = Modifier.testTag("search-submit")) {
                Text(stringResource(Res.string.m4_search))
            }
        }
        Row(Modifier.padding(horizontal = 16.dp)) {
            onVoice?.let { TextButton(it) { Text(stringResource(Res.string.m4_voice_search)) } }
            onRecognition?.let { TextButton(it) { Text(stringResource(Res.string.m4_recognize)) } }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = contentPadding) { contentRows(state, actions, artwork) }
    }
}
