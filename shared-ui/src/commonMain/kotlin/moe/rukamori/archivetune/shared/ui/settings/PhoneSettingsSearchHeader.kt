package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PhoneSettingsSearchHeader(back: () -> Unit) {
    LargeFlexibleTopAppBar(title = {
        Text(stringResource(Res.string.m4_settings), fontWeight = FontWeight.Bold)
    }, navigationIcon = {
        IconButton(onClick = back, modifier = Modifier.testTag("settings-back")) {
            Icon(painterResource(Res.drawable.m4_icon_arrow_back), stringResource(Res.string.m4_back))
        }
    }, windowInsets = WindowInsets(0, 0, 0, 0))
}

@Composable
internal fun PhoneSettingsSearchField(query: String, onQuery: (String) -> Unit) {
    TextField(query, onQuery, singleLine = true,
        placeholder = { Text(stringResource(Res.string.m6_settings_search_hint)) },
        leadingIcon = { Icon(painterResource(Res.drawable.m4_icon_search), null) },
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("settings-search"))
    Spacer(Modifier.height(12.dp))
}
