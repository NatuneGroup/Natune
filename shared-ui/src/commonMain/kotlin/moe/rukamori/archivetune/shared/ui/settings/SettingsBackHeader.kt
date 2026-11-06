package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
@Composable
internal fun SettingsBackHeader(back: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = back, modifier = Modifier.testTag("settings-back")) {
            Icon(
                painterResource(Res.drawable.m4_icon_arrow_back),
                contentDescription = stringResource(Res.string.m4_back),
            )
        }
    }
}
