package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The host's own pop for a plain settings page. Desktop and iOS have no system back gesture, so those hosts supply
 * their navigation here; Android keeps the default, so its settings pages are unchanged.
 */
val LocalSettingsBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The one pinned back control a settings page draws when its host supplies [LocalSettingsBack]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsBackHeader(back: () -> Unit, title: org.jetbrains.compose.resources.StringResource? = null) {
    if (title != null) {
        TopAppBar(title = { Text(stringResource(title)) }, navigationIcon = {
            SettingsBackButton(back, moe.rukamori.archivetune.shared.ui.navigation.LocalBackToMain.current ?: back,
                IconButtonDefaults.iconButtonColors(), Modifier.testTag("settings-back")) {
                Icon(painterResource(Res.drawable.m4_icon_arrow_back), stringResource(Res.string.m4_back))
            }
        }, windowInsets = WindowInsets(0, 0, 0, 0))
        return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (title != null) 64.dp else 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = back, modifier = Modifier.testTag("settings-back")) {
            Icon(
                painterResource(Res.drawable.m4_icon_arrow_back),
                contentDescription = stringResource(Res.string.m4_back),
            )
        }
        title?.let { androidx.compose.material3.Text(stringResource(it), style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }
    }
}
