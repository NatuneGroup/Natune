package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneSettingsSearchHeader(query: String, searching: Boolean, onQuery: (String) -> Unit,
    onSearching: (Boolean) -> Unit, back: () -> Unit) {
    TopAppBar(title = {
        if (searching) TextField(query, onQuery, singleLine = true, placeholder = { Text(stringResource(Res.string.m6_settings_search_hint)) })
        else Text(stringResource(Res.string.m4_settings))
    }, navigationIcon = {
        IconButton(onClick = { if (searching) { onSearching(false); onQuery("") } else back() }) {
            Icon(painterResource(Res.drawable.m4_icon_arrow_back), stringResource(Res.string.m4_back))
        }
    }, actions = {
        IconButton(onClick = { if (searching && query.isNotEmpty()) onQuery("") else onSearching(!searching) }) {
            Icon(painterResource(if (searching && query.isNotEmpty()) Res.drawable.m4_icon_close else Res.drawable.m4_icon_search), stringResource(Res.string.m6_settings_search_hint))
        }
    }, windowInsets = WindowInsets(0, 0, 0, 0))
}
