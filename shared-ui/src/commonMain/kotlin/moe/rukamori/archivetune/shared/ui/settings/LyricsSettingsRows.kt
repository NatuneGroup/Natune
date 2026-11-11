package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*

fun lyricsSettingsRows(navigate: (String) -> Unit): List<SettingsRow> = listOf(
    SettingsRow.Action("language_packs", Res.string.m6_language_packs, { navigate("settings/language_packs") }),
    SettingsRow.Action("providers", Res.string.m6_providers, { navigate("settings/lyrics/providers") }),
    SettingsRow.Action("romanization", Res.string.m6_romanization, { navigate("settings/lyrics/romanisation") }),
    SettingsRow.Choice("lyrics_mode", Res.string.m6_lyrics_mode, LyricsModeKey, "ENHANCED", listOf(
        SettingsRow.Option("V2", Res.string.m6_mode_v2),
        SettingsRow.Option("ENHANCED", Res.string.m6_mode_enhanced),
        SettingsRow.Option("SPOTIFY", Res.string.m6_mode_spotify),
        SettingsRow.Option("SIMPMUSIC", Res.string.m6_mode_simpmusic),
    )),
    SettingsRow.Toggle("lyrics_click", Res.string.m6_lyrics_click, LyricsClickKey, true),
    SettingsRow.Toggle("lyrics_scroll", Res.string.m6_lyrics_scroll, LyricsScrollKey, true),
    SettingsRow.Number("preload_queue_lyrics", Res.string.m6_lyrics_preload, QueueLyricsPreloadCountKey, 3, 0..10),
    SettingsRow.Number("global_lyrics_offset", Res.string.m6_global_lyrics_offset, GlobalLyricsOffsetMsKey, 0, -2_000..2_000),
)

/**
 * Word-sync is a preliminary sweep, not an override of the fallback or manual provider controls.
 * [lyprioBuiltInServer] is true when the host baked a Lyprio endpoint pair into the build — the only
 * endpoint the provider can have, since the Metrolist developers run the server and there is no user
 * setting for it. A build without the pair still lists the provider, toggled off and disabled, so the
 * row reads unavailable instead of pretending a server can be entered.
 */
fun lyricsProviderSettingsRows(
    preferences: PreferenceSnapshot,
    showOrder: () -> Unit,
    showStats: () -> Unit,
    checkEndpoints: () -> Unit,
    lyprioBuiltInServer: Boolean = false,
): List<SettingsRow> = buildList {
    val enabled = true
    // With the word-synced sweep on, sync quality picks the providers — the order row greys out and
    // says so rather than suggesting a choice nothing reads.
    val prioritize = preferences[PrioritizeWordSyncedLyricsKey, true]
    add(SettingsRow.Action("lyrics_provider_order", Res.string.m6_provider_order, showOrder,
        enabled = enabled && !prioritize,
        description = Res.string.m6_provider_order_word_sync.takeIf { prioritize }))
    add(SettingsRow.Toggle("prioritize_word_synced_lyrics", Res.string.m6_word_sync, PrioritizeWordSyncedLyricsKey, true,
        description = Res.string.m6_word_sync_fallback))
    LyricsProviderPreference.entries.filter { !it.name.startsWith("PAXSENIX_") }.forEach { provider ->
        provider.key?.let { key ->
            // With no bundled endpoint the toggle stays listed but reports unavailable, like the other
            // controls that grey out while their backing feature is absent.
            val lyprioMissing = provider == LyricsProviderPreference.LYPRIO && !lyprioBuiltInServer
            add(
                SettingsRow.Toggle(
                    provider.anchor(), provider.label(), key, provider.defaultEnabled,
                    description = Res.string.m6_lyprio_not_available.takeIf { lyprioMissing },
                    enabled = enabled && !lyprioMissing,
                    read = { snapshot -> !lyprioMissing && snapshot[key, provider.defaultEnabled] },
                ),
            )
        }
    }
    add(SettingsRow.Toggle("enable_bini_lyrics", Res.string.m6_bini, EnableBiniLyricsKey, true, enabled = enabled))
    add(SettingsRow.Toggle("enable_lrcred_lyrics", Res.string.m6_lrc_red, EnableLrcRedKey, true, enabled = enabled))
    add(SettingsRow.Toggle("enable_genius_lyrics", Res.string.m6_genius, EnableGeniusLyricsKey, false, enabled = enabled))
    add(SettingsRow.Toggle("enable_paxsenix_lyrics", Res.string.m6_paxsenix, EnablePaxsenixLyricsKey, true, enabled = enabled))
    if (preferences[EnablePaxsenixLyricsKey, true]) {
        add(SettingsRow.Text("paxsenix_api_key", Res.string.m6_api_key, PaxsenixApiKeyKey, masked = true, enabled = enabled))
        add(SettingsRow.Text("paxsenix_endpoint", Res.string.m6_endpoint, PaxsenixEndpointKey, valid = ::validLyricsEndpoint, enabled = enabled))
        add(SettingsRow.Action("paxsenix_stats", Res.string.m6_provider_stats, showStats, enabled))
        add(SettingsRow.Action("paxsenix_check_endpoints", Res.string.m6_check_endpoints, checkEndpoints, enabled))
        LyricsProviderPreference.entries.filter { it.name.startsWith("PAXSENIX_") }.forEach { provider ->
            add(SettingsRow.Toggle(provider.anchor(), provider.label(), checkNotNull(provider.key), provider.defaultEnabled, enabled = enabled))
        }
    }
}

/**
 * The five per-language toggles of the romanisation page. An active AI romanisation replaces every engine on the page,
 * so the switches grey out behind the override note instead of flipping while changing nothing — the original's
 * `romanization_overridden_by_ai`/`language_pack_required` descriptions carry over verbatim.
 */
fun romanizationSettingsRows(
    aiHandled: Boolean, japanesePackReady: Boolean, preferences: PreferenceSnapshot,
): List<SettingsRow> {
    val override = Res.string.m6_romanization_overridden_by_ai.takeIf { aiHandled }
    val japaneseReady = japanesePackReady || preferences[LyricsRomanizeJapaneseKey, false]
    return listOf(
        SettingsRow.Toggle("lyrics_romanize_japanese", Res.string.m6_japanese, LyricsRomanizeJapaneseKey, false,
            description = override ?: Res.string.m6_language_pack_required.takeIf { !japaneseReady },
            enabled = !aiHandled && japaneseReady),
        SettingsRow.Toggle("lyrics_romanize_korean", Res.string.m6_korean, LyricsRomanizeKoreanKey, true,
            description = override, enabled = !aiHandled),
        SettingsRow.Toggle("lyrics_romanize_chinese", Res.string.m6_chinese, LyricsRomanizeChineseKey, true,
            description = override, enabled = !aiHandled),
        SettingsRow.Toggle("lyrics_romanize_hindi", Res.string.m6_hindi, LyricsRomanizeHindiKey, true,
            description = override, enabled = !aiHandled),
        SettingsRow.Toggle("lyrics_romanize_other", Res.string.m6_other_languages, LyricsRomanizeOtherLanguagesKey, true,
            description = override, enabled = !aiHandled),
    )
}

fun validLyricsEndpoint(value: String): Boolean =
    value.isBlank() || (value.startsWith("https://") || value.startsWith("http://")) &&
        value.substringAfter("://").substringBefore('/').isNotBlank() && value.none(Char::isWhitespace)

internal fun LyricsProviderPreference.label() = when (this) {
    LyricsProviderPreference.LYPRIO -> Res.string.m6_lyprio
    LyricsProviderPreference.BETTER_LYRICS -> Res.string.m6_betterlyrics
    LyricsProviderPreference.BETTER_LYRICS_PORTATO -> Res.string.m6_portato
    LyricsProviderPreference.YOULY_PLUS -> Res.string.m6_youlyplus
    LyricsProviderPreference.LRCLIB -> Res.string.m6_lrclib
    LyricsProviderPreference.KUGOU -> Res.string.m6_kugou
    LyricsProviderPreference.MEGALOBIZ -> Res.string.m6_megalobiz
    LyricsProviderPreference.SIMPMUSIC -> Res.string.m6_simpmusic
    LyricsProviderPreference.UNISON -> Res.string.m6_unison
    LyricsProviderPreference.APPLE_MUSIC -> Res.string.m6_apple_music
    LyricsProviderPreference.PAXSENIX_APPLE_MUSIC -> Res.string.m6_pax_apple
    LyricsProviderPreference.TIDAL -> Res.string.m6_tidal
    LyricsProviderPreference.DEEZER -> Res.string.m6_deezer
    LyricsProviderPreference.MUSIXMATCH_EXPERIMENTAL -> Res.string.m6_musixmatch_experimental
}

private fun LyricsProviderPreference.anchor() = when (this) {
    LyricsProviderPreference.LYPRIO -> "enable_lyprio_lyrics"
    LyricsProviderPreference.BETTER_LYRICS -> "enable_betterlyrics"
    LyricsProviderPreference.BETTER_LYRICS_PORTATO -> "enable_betterlyrics_portato"
    LyricsProviderPreference.YOULY_PLUS -> "enable_youlyplus_lyrics"
    LyricsProviderPreference.LRCLIB -> "enable_lrclib"
    LyricsProviderPreference.KUGOU -> "enable_kugou"
    LyricsProviderPreference.MEGALOBIZ -> "enable_megalobiz_lyrics"
    LyricsProviderPreference.SIMPMUSIC -> "enable_simpmusic_lyrics"
    LyricsProviderPreference.UNISON -> "enable_unison_lyrics"
    LyricsProviderPreference.APPLE_MUSIC -> "apple_music_lyrics"
    LyricsProviderPreference.PAXSENIX_APPLE_MUSIC -> "enable_paxsenix_apple_music_lyrics"
    LyricsProviderPreference.TIDAL -> "enable_tidal_lyrics"
    LyricsProviderPreference.DEEZER -> "enable_deezer_lyrics"
    LyricsProviderPreference.MUSIXMATCH_EXPERIMENTAL -> "enable_musixmatch_experimental"
}
