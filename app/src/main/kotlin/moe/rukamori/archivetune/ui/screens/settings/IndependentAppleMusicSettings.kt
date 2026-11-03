package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.kmp.settings.sharedSnapshot
import moe.rukamori.archivetune.shared.domain.settings.PlayerMenuStyle
import moe.rukamori.archivetune.shared.domain.settings.appleMusicMenus
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.PreferenceGroupScope
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.rememberInterfaceStyle
import moe.rukamori.archivetune.ui.component.rememberPlayerMenuStyleSetter
import moe.rukamori.archivetune.utils.PreferenceSnapshot

private val appleMusicSurfaceOptions = listOf(
    Triple("apple_music_menus", AppleMusicMenusKey, R.string.apple_music_menus),
    Triple("apple_music_search_bar", AppleMusicSearchBarKey, R.string.apple_music_search_bar),
    Triple("apple_music_palette", AppleMusicPaletteKey, R.string.apple_music_palette),
)

/** Controls only; the Appearance layout owner decides their placement. */
internal fun PreferenceGroupScope.independentAppleMusicPreferences(prefs: PreferenceSnapshot, positions: PreferencePositions) {
    item {
        val (style, change) = rememberInterfaceStyle(prefs)
        EnumListPreference(
            modifier = positions.modifierFor("interface_style", "apple_music_experience"),
            title = { Text(stringResource(R.string.interface_style)) },
            description = stringResource(R.string.interface_style_desc),
            icon = { Icon(painterResource(R.drawable.palette), null) },
            selectedValue = style,
            onValueSelected = change,
            valueText = {
                stringResource(when (it) {
                    InterfaceStyle.MATERIAL_EXPRESSIVE -> R.string.interface_style_material_expressive
                    InterfaceStyle.APPLE_MUSIC -> R.string.apple_music_experience
                    InterfaceStyle.CUSTOM -> R.string.interface_style_custom_mixed
                })
            },
            isValueEnabled = { it != InterfaceStyle.CUSTOM },
        )
    }
    appleMusicSurfaceOptions.forEach { (anchor, key, title) ->
        item {
            val setMenuStyle = rememberPlayerMenuStyleSetter()
            SwitchPreference(
                modifier = positions.modifierFor(anchor),
                title = { Text(stringResource(title)) },
                description = stringResource(
                    if (key == AppleMusicPaletteKey) R.string.apple_music_palette_desc
                    else R.string.apple_music_independent_option_desc,
                ),
                icon = { Icon(painterResource(R.drawable.palette), null) },
                checked = if (key == AppleMusicMenusKey) prefs.current.sharedSnapshot().appleMusicMenus() else prefs[key, false],
                onCheckedChange = {
                    if (key == AppleMusicMenusKey) {
                        setMenuStyle(if (it) PlayerMenuStyle.APPLE_MUSIC else PlayerMenuStyle.MD3)
                    } else {
                        prefs.state(key, false).value = it
                    }
                },
            )
        }
    }
}

internal fun independentAppleMusicSearchChildren(context: Context): List<SettingsChild> =
    appleMusicSurfaceOptions.map { (anchor, key, title) ->
        SettingsChild(context.getString(title), anchor, listOf("apple music", context.getString(title))) {
            SearchResultSwitch(key, false)
        }
    }
