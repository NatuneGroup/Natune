package moe.rukamori.archivetune.shared.ui.settings

/** Android screen references used only by the phone settings host. */
fun phoneSettingsReference(route: String): PhoneSettingsReference? = when (route.substringBefore('?')) {
    "settings/appearance" -> PhoneAppearanceSettingsReference
    "settings/appearance/extras" -> PhoneAppearanceExtrasSettingsReference
    "settings/appearance/navigation_bar" -> PhoneNavigationBarSettingsReference
    "settings/appearance/lyrics_animations" -> PhoneLyricsAnimationSettingsReference
    "settings/appearance/liquid_glass" -> PhoneLiquidGlassSettingsReference
    "settings/appearance/aod_customized" -> PhoneAodCustomizedScreenReference
    "settings/player" -> PhonePlayerSettingsReference
    "settings/lyrics" -> PhoneLyricsSettingsReference
    "settings/lyrics/providers" -> PhoneLyricsProvidersSettingsReference
    "settings/lyrics/romanisation" -> PhoneLyricsRomanisationSettingsReference
    "settings/content" -> PhoneContentSettingsReference
    "settings/privacy" -> PhonePrivacySettingsReference
    "settings/misc" -> PhoneDebugSettingsReference
    "settings/language_packs" -> PhoneLanguagePackSettingsReference
    "settings/android_auto" -> PhoneAndroidAutoSettingsReference
    "settings/jiosaavn" -> PhoneJioSettingsReference
    "settings/integration" -> PhoneIntegrationScreenReference
    "settings/account" -> PhoneAccountSettingsReference
    "settings/tidal" -> PhoneTidalSettingsReference
    "settings/qobuz" -> PhoneQobuzSettingsReference
    "settings/deezer" -> PhoneDeezerSettingsReference
    "settings/amazon" -> PhoneAmazonSettingsReference
    "settings/applemusic" -> PhoneAppleMusicSettingsReference
    "settings/qqmusic" -> PhoneQqMusicSettingsReference
    "settings/spotify_streaming" -> PhoneSpotifyStreamingSettingsReference
    "settings/telegram" -> PhoneTelegramSettingsReference
    "settings/discord" -> PhoneDiscordSettingsReference
    "settings/discord/experimental" -> PhoneDiscordExperimentalReference
    "settings/internet" -> PhoneInternetSettingsReference
    "settings/downloads" -> PhoneDownloadsSettingsReference
    "settings/storage" -> PhoneStorageSettingsReference
    "settings/canvas" -> PhoneCanvasSettingsReference
    "settings/ai_integration" -> PhoneAiIntegrationSettingsReference
    "settings/appearance/theme", "settings/appearance/player", "settings/appearance/interface" -> PhoneAppearanceSettingsReference
    else -> null
}
