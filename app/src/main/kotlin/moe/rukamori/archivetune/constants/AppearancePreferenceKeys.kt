package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Preset status; CUSTOM is a derived indicator, never a preset to apply. */
enum class InterfaceStyle { MATERIAL_EXPRESSIVE, APPLE_MUSIC, CUSTOM }

val AppleMusicHeadersKey = booleanPreferencesKey("apple_music_headers")
val AppleMusicMenusKey = booleanPreferencesKey("apple_music_menus")
val AppleMusicSearchBarKey = booleanPreferencesKey("apple_music_search_bar")
val AppleMusicPaletteKey = booleanPreferencesKey("apple_music_palette")
val AppleMusicSettingsMigratedKey = booleanPreferencesKey("independent_apple_music_settings")
val NavigationStyleBeforeAppleMusicKey = stringPreferencesKey("navigation_style_before_apple_music")
