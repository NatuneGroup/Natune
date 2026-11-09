package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneLyricsProvidersSettingsReference = PhoneSettingsReference(Res.string.m6_providers, listOf(
    PhoneSettingReference("group:providers", Res.string.m6_providers, null, PhoneSettingKind.HEADER, setOf("providers"), null, setOf(Res.string.m6_providers), false),
    PhoneSettingReference("extensions_lyrics_manage", Res.string.phone_reference_extensions_lyrics_manage, Res.drawable.phone_reference_extension, PhoneSettingKind.ACTION, setOf("extensions_lyrics_manage"), null, setOf(), false),
    PhoneSettingReference("extensions_lyrics_first", Res.string.phone_reference_extensions_lyrics_first, null, PhoneSettingKind.TOGGLE, setOf("extensions_lyrics_first"), null, setOf(), false),
    PhoneSettingReference("prioritize_word_synced_lyrics", Res.string.m6_word_sync, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("prioritize_word_synced_lyrics"), "prioritizeWordSyncedLyrics", setOf(Res.string.m6_word_sync), false),
    PhoneSettingReference("enable_lyprio_lyrics", Res.string.phone_reference_enable_lyprio_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_lyprio_lyrics"), null, setOf(), false),
    PhoneSettingReference("enable_betterlyrics", Res.string.phone_reference_enable_betterlyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_betterlyrics"), "enableBetterLyrics", setOf(), false),
    PhoneSettingReference("enable_betterlyrics_portato", Res.string.phone_reference_enable_betterlyrics_portato, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_betterlyrics_portato"), "enableBetterLyricsPortato", setOf(), false),
    PhoneSettingReference("enable_youlyplus_lyrics", Res.string.phone_reference_enable_youlyplus_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_youlyplus_lyrics"), "enableYouLyPlusLyrics", setOf(), false),
    PhoneSettingReference("enable_lrclib", Res.string.phone_reference_enable_lrclib, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_lrclib"), "enableLrclib", setOf(), false),
    PhoneSettingReference("enable_kugou", Res.string.phone_reference_enable_kugou, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_kugou"), "enableKugou", setOf(), false),
    PhoneSettingReference("enable_unison_lyrics", Res.string.phone_reference_enable_unison_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_unison_lyrics"), "enableUnisonLyrics", setOf(), false),
    PhoneSettingReference("enable_simpmusic_lyrics", Res.string.phone_reference_enable_simpmusic_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_simpmusic_lyrics"), "enableSimpMusicLyrics", setOf(), false),
    PhoneSettingReference("enable_megalobiz_lyrics", Res.string.phone_reference_enable_megalobiz_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_megalobiz_lyrics"), "enableMegalobizLyrics", setOf(), false),
    PhoneSettingReference("enable_bini_lyrics", Res.string.phone_reference_enable_bini_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_bini_lyrics"), "enableBiniLyrics", setOf(), false),
    PhoneSettingReference("enable_lrcred_lyrics", Res.string.phone_reference_enable_lrcred_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_lrcred_lyrics"), "enableLrcRed", setOf(), false),
    PhoneSettingReference("enable_genius_lyrics", Res.string.phone_reference_enable_genius_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_genius_lyrics"), "enableGeniusLyrics", setOf(), false),
    PhoneSettingReference("enable_paxsenix_lyrics", Res.string.phone_reference_enable_paxsenix_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_paxsenix_lyrics"), "enablePaxsenixLyrics", setOf(), false),
    PhoneSettingReference("paxsenix_stats", Res.string.phone_reference_paxsenix_stats, Res.drawable.phone_reference_stats, PhoneSettingKind.ACTION, setOf("paxsenix_stats"), null, setOf(), true),
    PhoneSettingReference("paxsenix_api_key", Res.string.phone_reference_paxsenix_api_key, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("paxsenix_api_key"), null, setOf(), true),
    PhoneSettingReference("paxsenix_endpoint", Res.string.phone_reference_paxsenix_endpoint, Res.drawable.phone_reference_solar_server_linear, PhoneSettingKind.ACTION, setOf("paxsenix_endpoint"), null, setOf(), true),
    PhoneSettingReference("paxsenix_check_endpoints", Res.string.phone_reference_paxsenix_check_endpoints, Res.drawable.phone_reference_wifi_proxy, PhoneSettingKind.ACTION, setOf("paxsenix_check_endpoints"), null, setOf(), true),
    PhoneSettingReference("enable_tidal_lyrics", Res.string.phone_reference_enable_tidal_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_tidal_lyrics"), "enableTidalLyrics", setOf(), false),
    PhoneSettingReference("enable_deezer_lyrics", Res.string.phone_reference_enable_deezer_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_deezer_lyrics"), "enableDeezerLyrics", setOf(), false),
    PhoneSettingReference("set_first_lyrics_provider", Res.string.phone_reference_set_first_lyrics_provider, Res.drawable.phone_reference_lyrics, PhoneSettingKind.ACTION, setOf("set_first_lyrics_provider"), null, setOf(), false),
    PhoneSettingReference("group:musixmatch_experimental_section", Res.string.phone_reference_musixmatch_experimental_section, null, PhoneSettingKind.HEADER, setOf("musixmatch_experimental_section"), null, setOf(), false),
    PhoneSettingReference("enable_musixmatch_experimental", Res.string.m6_musixmatch_experimental, Res.drawable.phone_reference_lyrics, PhoneSettingKind.TOGGLE, setOf("enable_musixmatch_experimental"), "enableMusixmatchExperimental", setOf(Res.string.m6_musixmatch_experimental), false),
))
