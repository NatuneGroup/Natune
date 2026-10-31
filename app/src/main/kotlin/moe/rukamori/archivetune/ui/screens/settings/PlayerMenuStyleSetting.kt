package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.domain.settings.PlayerMenuStyle
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.playerMenuStyle
import moe.rukamori.archivetune.ui.component.rememberPlayerMenuStyleSetter
import moe.rukamori.archivetune.utils.PreferenceSnapshot

/**
 * Appearance > Player: the three-dot and lyrics menu look. The write goes through the shared rule, which keeps the
 * legacy Apple menus toggle in step; a plain enum preference would leave that toggle deciding the look.
 */
@Composable
fun PlayerMenuStyleSetting(
    prefs: PreferenceSnapshot,
    modifier: Modifier = Modifier,
) {
    EnumListPreference(
        modifier = modifier,
        title = { Text(stringResource(R.string.player_menu_style)) },
        description = stringResource(R.string.player_menu_style_desc),
        icon = { Icon(painterResource(R.drawable.palette), null) },
        selectedValue = prefs.playerMenuStyle(),
        onValueSelected = rememberPlayerMenuStyleSetter(),
        valueText = { style ->
            stringResource(
                if (style == PlayerMenuStyle.MD3) R.string.player_menu_style_md3 else R.string.player_menu_style_apple_music,
            )
        },
    )
}
