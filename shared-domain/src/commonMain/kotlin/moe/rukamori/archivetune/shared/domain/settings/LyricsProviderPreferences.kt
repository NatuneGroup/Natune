package moe.rukamori.archivetune.shared.domain.settings

/** Persisted order ids and provider names are wire identities, never translated picker captions. */
enum class LyricsProviderPreference(val providerName: String, val key: BooleanPreferenceKey?, val defaultEnabled: Boolean = true) {
    /** A server the user hosts; off until enabled with its URL and signing key. First, as in Metrolist's default order. */
    LYPRIO("Lyprio", EnableLyprioLyricsKey, false),
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
    PAXSENIX_NETEASE("Paxsenix: NetEase", EnablePaxsenixNeteaseLyricsKey, false),
    PAXSENIX_SPOTIFY("Paxsenix: Spotify", EnablePaxsenixSpotifyLyricsKey, false),
    PAXSENIX_MUSIXMATCH("Paxsenix: Musixmatch", EnablePaxsenixMusixmatchLyricsKey, false),
    PAXSENIX_YOUTUBE("Paxsenix: YouTube", EnablePaxsenixYouTubeLyricsKey, false),
    TIDAL("Tidal", EnableTidalLyricsKey),
    DEEZER("Deezer", EnableDeezerLyricsKey),
    MUSIXMATCH_EXPERIMENTAL("Musixmatch (experimental)", EnableMusixmatchExperimentalKey, false),
}

fun PreferenceSnapshot.lyricsProviderOrder(): List<LyricsProviderPreference> {
    val parsed = this[LyricsProviderOrderKey].orEmpty().split(',').mapNotNull { raw ->
        LyricsProviderPreference.entries.firstOrNull { it.name == raw.trim() }
    }.distinct()
    val normalized = if (parsed.take(3) == listOf(
        LyricsProviderPreference.LRCLIB, LyricsProviderPreference.KUGOU, LyricsProviderPreference.BETTER_LYRICS,
    )) listOf(LyricsProviderPreference.BETTER_LYRICS) + parsed.filterNot { it == LyricsProviderPreference.BETTER_LYRICS }
    else parsed
    return normalized + LyricsProviderPreference.entries.filterNot { it in normalized }
}

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
