package moe.rukamori.archivetune.shared.domain.settings

/** Persisted order ids and provider names are wire identities, never translated picker captions. */
enum class LyricsProviderPreference(val providerName: String, val key: BooleanPreferenceKey?, val defaultEnabled: Boolean = true) {
    /** Metrolist's hosted lyrics server, bundled at build time. On and first by default, as in Metrolist. */
    LYPRIO("Lyprio", EnableLyprioLyricsKey),
    BETTER_LYRICS("BetterLyrics", EnableBetterLyricsKey),
    BETTER_LYRICS_PORTATO("BetterLyrics Portato", EnableBetterLyricsPortatoKey),
    YOULY_PLUS("YouLyPlus", EnableYouLyPlusLyricsKey),
    LRCLIB("LrcLib", EnableLrcLibKey),
    KUGOU("Kugou", EnableKugouKey),
    MEGALOBIZ("Megalobiz", EnableMegalobizLyricsKey),
    SIMPMUSIC("SimpMusic", EnableSimpMusicLyricsKey),
    UNISON("Unison", EnableUnisonLyricsKey),
    PAXSENIX_APPLE_MUSIC("Paxsenix: Apple Music", EnablePaxsenixAppleMusicLyricsKey),
    APPLE_MUSIC("Apple Music", null),
    TIDAL("Tidal", EnableTidalLyricsKey),
    DEEZER("Deezer", EnableDeezerLyricsKey),
    MUSIXMATCH_EXPERIMENTAL("Musixmatch (experimental)", EnableMusixmatchExperimentalKey, false),
}

fun parseLyricsProviderOrder(order: String?): List<LyricsProviderPreference> {
    val parsed = order.orEmpty().split(',').mapNotNull { raw ->
        LyricsProviderPreference.entries.firstOrNull { it.name == raw.trim() }
    }.distinct()
    return parsed + LyricsProviderPreference.entries.filterNot { it in parsed }
}

fun PreferenceSnapshot.lyricsProviderOrder(): List<LyricsProviderPreference> = parseLyricsProviderOrder(this[LyricsProviderOrderKey])

fun PreferenceEditor.setLyricsProviderOrder(order: List<LyricsProviderPreference>) {
    require(order.distinct().size == order.size)
    require(order.toSet() == LyricsProviderPreference.entries.toSet())
    this[LyricsProviderOrderKey] = order.joinToString(",") { it.name }
}

fun PreferenceSnapshot.lyricsProviderEnabled(providerName: String): Boolean {
    val preference = LyricsProviderPreference.entries.firstOrNull { it.providerName == providerName }
    if (preference != null) return preference.key?.let { this[it, preference.defaultEnabled] } ?: true
    return when (providerName) {
        "BiniLyrics" -> this[EnableBiniLyricsKey, true]
        "lrc.red" -> this[EnableLrcRedKey, true]
        "Genius" -> this[EnableGeniusLyricsKey, false]
        else -> true
    }
}
