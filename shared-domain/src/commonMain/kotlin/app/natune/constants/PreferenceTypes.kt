package app.natune.constants

// The value types and helpers that the preference keys store.

const val SYSTEM_DEFAULT = "SYSTEM_DEFAULT"
/**
 * Helpers for the download-source priority list. Mirrors [AudioSourceConfig] in the audiosource
 * package — same CSV serialization, append-missing-on-parse semantics, and a single DEFAULT_ORDER.
 *
 * The three sources [QOBUZ], [TIDAL], [DEEZER] require a Source Pool account to be configured
 * (see `PoolAccountManager.isEnabled`). [JIOSAAVN] uses the public JioSaavn API (no pool needed).
 * [YOUTUBE_MUSIC] always works.
 */
object DownloadSourceConfig {
    val DEFAULT_ORDER: List<DownloadSource> =
        listOf(
            DownloadSource.QOBUZ,
            DownloadSource.QOBUZ_BACKUP,
            DownloadSource.TIDAL,
            DownloadSource.DEEZER,
            DownloadSource.JIOSAAVN,
            DownloadSource.MODULES,
            DownloadSource.YOUTUBE_MUSIC,
        )

    /** Sources that need a Source Pool account configured to be usable for downloads. */
    val REQUIRES_POOL: Set<DownloadSource> =
        setOf(DownloadSource.QOBUZ, DownloadSource.TIDAL, DownloadSource.DEEZER)

    /** Cache-key prefix a source's downloaded bytes are stored under, e.g. `"qobuz_backup:"`. */
    val CACHE_KEY_PREFIXES: List<String> =
        DownloadSource.entries
            .filterNot { it == DownloadSource.AUTO || it == DownloadSource.YOUTUBE_MUSIC }
            .map { "${it.name.lowercase()}:" }

    /**
     * Every cache key a song's bytes may live under, most specific first: each source's prefix,
     * then the bare id the playback cache also uses. The one definition the download path, its
     * purge and playback's cache probes share, so bytes are never written under a key playback
     * does not look at.
     */
    fun cacheKeysFor(mediaId: String): List<String> = CACHE_KEY_PREFIXES.map { "$it$mediaId" } + mediaId

    private fun parseType(name: String): DownloadSource? =
        runCatching { DownloadSource.valueOf(name.trim().uppercase()) }.getOrNull()

    /**
     * Resolves the effective ordered list of all download sources from the stored CSV, preserving
     * the user's chosen order and slotting any missing sources in above
     * [DownloadSource.YOUTUBE_MUSIC].
     */
    fun parseOrder(rawOrder: String?): List<DownloadSource> {
        val stored =
            rawOrder
                ?.split(',')
                ?.mapNotNull { parseType(it) }
                ?.distinct()
                .orEmpty()
        if (stored.isEmpty()) return DEFAULT_ORDER

        val missing = DEFAULT_ORDER.filterNot { it in stored }
        if (missing.isEmpty()) return stored

        val merged = mutableListOf<DownloadSource>()
        var inserted = false
        for (source in stored) {
            if (!inserted && source == DownloadSource.YOUTUBE_MUSIC) {
                merged.addAll(missing)
                inserted = true
            }
            merged.add(source)
        }
        // YouTube Music absent from the stored order means it is itself missing, and it is last in
        // DEFAULT_ORDER, so appending the block still leaves it as the final fallback.
        if (!inserted) merged.addAll(missing)
        return merged
    }

    /** Serialize an order back to the CSV form for storage. */
    fun serialize(order: List<DownloadSource>): String = order.joinToString(",") { it.name }

    /**
     * The ordered list of sources a download may fetch from: the user's chosen order up to — but
     * not past — [DownloadSource.YOUTUBE_MUSIC]. YouTube Music is the chain's terminal entry, the
     * way [AudioSourceType.YOUTUBE] ends the playback chain (MusicService cuts it with `takeWhile`):
     * a lossless source only stands in for YouTube when the user placed it above YouTube, and
     * sources below YouTube are never reached. A caller that exhausts this list falls through to
     * the YouTube resolver, which is exactly playback's fallback.
     */
    fun fetchChain(rawOrder: String?): List<DownloadSource> =
        parseOrder(rawOrder).takeWhile { it != DownloadSource.YOUTUBE_MUSIC }

    /**
     * Maps a playback source to the download source serving the same catalogue. Returns null for
     * the playback-only sources this path has no resolver for (Apple, Amazon, QQ, Spotify) so a pin
     * to one of them falls back to the order rather than pinning the download to a source it can
     * never resolve.
     */
    fun fromAudioSource(source: AudioSourceType): DownloadSource? =
        when (source) {
            AudioSourceType.QOBUZ -> DownloadSource.QOBUZ
            AudioSourceType.QOBUZ_BACKUP -> DownloadSource.QOBUZ_BACKUP
            AudioSourceType.TIDAL -> DownloadSource.TIDAL
            AudioSourceType.DEEZER -> DownloadSource.DEEZER
            AudioSourceType.JIOSAAVN -> DownloadSource.JIOSAAVN
            AudioSourceType.MODULES -> DownloadSource.MODULES
            AudioSourceType.YOUTUBE -> DownloadSource.YOUTUBE_MUSIC
            AudioSourceType.APPLE, AudioSourceType.AMAZON, AudioSourceType.QQ, AudioSourceType.SPOTIFY -> null
        }

    /**
     * The download chain for one song. A per-song [pinnedSource] — the user's "Play from" choice,
     * which is also the source playback used — *is* the chain: pinning YouTube yields an empty list
     * so the caller uses the YouTube resolver, and pinning a source this path cannot resolve falls
     * back to the order. Without a pin the chain is [fetchChain].
     *
     * When [playbackEnabled] is given, sources whose playback toggle is off are skipped:
     * the download order stays its own, but a source that playback would
     * not use is not used for downloads either, and a pin to a disabled source yields to the
     * order the way playback's stale pins do. Pass null to keep the ungated chain (tests,
     * offline exports).
     */
    fun resolutionChain(
        rawOrder: String?,
        pinnedSource: AudioSourceType?,
        playbackEnabled: Set<AudioSourceType>? = null,
    ): List<DownloadSource> {
        val pinned = pinnedSource?.let(::fromAudioSource)
        val pinnedActive = pinned != null && (playbackEnabled == null || pinnedSource in playbackEnabled)
        val chain =
            when {
                !pinnedActive -> fetchChain(rawOrder)
                pinned == DownloadSource.YOUTUBE_MUSIC -> emptyList()
                else -> listOf(pinned)
            }
        return if (playbackEnabled == null) {
            chain
        } else {
            chain.filter { source -> toAudioSource(source)?.let(playbackEnabled::contains) ?: true }
        }
    }

    /**
     * Whether the terminal YouTube resolver may serve a download. It sits after every
     * [resolutionChain] but is not part of it, so its playback toggle has to be checked here.
     */
    fun youTubeFallbackAllowed(playbackEnabled: Set<AudioSourceType>?): Boolean =
        playbackEnabled == null || AudioSourceType.YOUTUBE in playbackEnabled

    /**
     * The already-cached keys a download may reuse for [mediaId], in the same order and under
     * the same gates as a fresh resolution: the [resolutionChain] sources, then YouTube's bare key
     * only while [youTubeFallbackAllowed]. A cache hit for any other source would hand the user
     * bytes from a source they disabled or ranked below YouTube.
     */
    fun downloadCacheKeys(
        rawOrder: String?,
        pinnedSource: AudioSourceType?,
        playbackEnabled: Set<AudioSourceType>?,
        mediaId: String,
    ): List<String> =
        resolutionChain(rawOrder, pinnedSource, playbackEnabled)
            .filterNot { it == DownloadSource.AUTO }
            .map { "${it.name.lowercase()}:$mediaId" } +
            listOfNotNull(mediaId.takeIf { youTubeFallbackAllowed(playbackEnabled) })

    /** The playback source serving the same catalogue as [source], null for [DownloadSource.AUTO]. */
    fun toAudioSource(source: DownloadSource): AudioSourceType? =
        when (source) {
            DownloadSource.QOBUZ -> AudioSourceType.QOBUZ
            DownloadSource.QOBUZ_BACKUP -> AudioSourceType.QOBUZ_BACKUP
            DownloadSource.TIDAL -> AudioSourceType.TIDAL
            DownloadSource.DEEZER -> AudioSourceType.DEEZER
            DownloadSource.JIOSAAVN -> AudioSourceType.JIOSAAVN
            DownloadSource.MODULES -> AudioSourceType.MODULES
            DownloadSource.YOUTUBE_MUSIC -> AudioSourceType.YOUTUBE
            DownloadSource.AUTO -> null
        }
}
// The fork's UI flag has always been this alias (stored under "networkMetered"):
// Low data mode ON means "treat networks as metered". The seemingly opposite
// defaults (UI on vs metered off) are two toggles over one persisted bit,
// pre-existing fork semantics — not something this port may redefine.
val LowDataModeKey = NetworkMeteredKey
val DefaultLyricsProviderOrder =
    listOf(
        PreferredLyricsProvider.BUNDLED,
        PreferredLyricsProvider.BETTER_LYRICS,
        PreferredLyricsProvider.BETTER_LYRICS_PORTATO,
        PreferredLyricsProvider.YOULY_PLUS,
        PreferredLyricsProvider.LRCLIB,
        PreferredLyricsProvider.KUGOU,
        PreferredLyricsProvider.MEGALOBIZ,
        PreferredLyricsProvider.SIMPMUSIC,
        PreferredLyricsProvider.UNISON,
        PreferredLyricsProvider.PAXSENIX_APPLE_MUSIC,
        PreferredLyricsProvider.APPLE_MUSIC,
        PreferredLyricsProvider.PAXSENIX_NETEASE,
        PreferredLyricsProvider.PAXSENIX_SPOTIFY,
        PreferredLyricsProvider.PAXSENIX_MUSIXMATCH,
        PreferredLyricsProvider.PAXSENIX_YOUTUBE,
        PreferredLyricsProvider.TIDAL,
        PreferredLyricsProvider.DEEZER,
        PreferredLyricsProvider.MUSIXMATCH_EXPERIMENTAL,
    )
fun deserializeLyricsProviderOrder(orderStr: String?): List<PreferredLyricsProvider> {
    if (orderStr.isNullOrBlank()) return DefaultLyricsProviderOrder

    val parsed =
        orderStr
            .split(",")
            .mapNotNull { name ->
                PreferredLyricsProvider.entries.find { it.name == name.trim() }
            }.distinct()

    val normalized =
        when {
            parsed.take(3) ==
                listOf(
                    PreferredLyricsProvider.LRCLIB,
                    PreferredLyricsProvider.KUGOU,
                    PreferredLyricsProvider.BETTER_LYRICS,
                )
            -> listOf(PreferredLyricsProvider.BETTER_LYRICS) + parsed.filterNot { it == PreferredLyricsProvider.BETTER_LYRICS }

            else -> parsed
        }

    val missing = DefaultLyricsProviderOrder.filterNot { it in normalized }
    return normalized + missing
}
val DefaultArtworkProviderOrder =
    listOf(
        PreferredArtworkProvider.LOCAL_EMBEDDED,
        PreferredArtworkProvider.ORIGINAL_METADATA,
        PreferredArtworkProvider.TIDAL,
        PreferredArtworkProvider.SPOTIFY_CANVAS,
        PreferredArtworkProvider.ARCHIVETUNE_CANVAS,
    )
fun deserializeArtworkProviderOrder(orderStr: String?): List<PreferredArtworkProvider> {
    if (orderStr.isNullOrBlank()) return DefaultArtworkProviderOrder

    val parsed =
        orderStr
            .split(",")
            .mapNotNull { name ->
                PreferredArtworkProvider.entries.find { it.name == name.trim() }
            }.distinct()

    val missing = DefaultArtworkProviderOrder.filterNot { it in parsed }
    return parsed + missing
}
const val NAVIGATION_BAR_WIDTH_DEFAULT = 0.8f // fraction of screen width when FLOATING
const val NAVIGATION_BAR_HEIGHT_DEFAULT = 1.0f // multiplier on NavigationBarHeight
const val NAVIGATION_BAR_OPACITY_DEFAULT = 1.0f // 1 = opaque, <1 = translucent
const val NAVIGATION_BAR_TRANSPARENCY_DEFAULT = 0.0f // 0 = solid, >0 = see-through (only when frosted blur is off)
const val NAVIGATION_BAR_LABEL_SPACING_DEFAULT = 4f // dp between icon and label
const val NAVIGATION_BAR_CORNER_RADIUS_DEFAULT = 28f // dp
const val HISTORY_DURATION_DEFAULT = 30
const val HISTORY_DURATION_MIN = 1
const val HISTORY_DURATION_MAX = 60
val HISTORY_DURATION_RANGE = HISTORY_DURATION_MIN.toFloat()..HISTORY_DURATION_MAX.toFloat()
fun QobuzAudioQuality.toFormatId(): Int =
    when (this) {
        QobuzAudioQuality.FLAC -> 6
        QobuzAudioQuality.HI_RES -> 7
        QobuzAudioQuality.MAX -> 27
    }
val LanguageCodeToName =
    mapOf(
        "en" to "English (US)",
        "en-GB" to "English (UK)",
        "en-NG" to "English (Nigeria)",
        "ja" to "日本語",
        "ko" to "한국어",
        "vi" to "Tiếng Việt",
        "zh" to "中文",
        "zh-CN" to "简体中文",
        "zh-TW" to "繁���中文",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "pt" to "Português",
        "pt-BR" to "Português (Brasil)",
        "ru" to "Русский",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "hi" to "हिन्दी",
        "th" to "ไทย",
        "id" to "Bahasa Indonesia",
        "ms" to "Bahasa Melayu",
        "uk" to "Українська",
        "cs" to "Čeština",
        "el" to "Ελληνικά",
        "he" to "עברית",
        "hu" to "Magyar",
        "ro" to "Română",
        "fi" to "Suomi",
        "da" to "Dansk",
        "no" to "Norsk",
        "sv" to "Svenska",
        "sk" to "Slovenčina",
        "bg" to "Български",
        "hr" to "Hrvatski",
        "sr" to "Срpsки",
        "lt" to "Lietuvių",
        "lv" to "Latviešu",
        "et" to "Eesti",
    )
val CountryCodeToName =
    mapOf(
        "JP" to "Japan",
        "KR" to "South Korea",
        "US" to "United States",
        "GB" to "United Kingdom",
        "CN" to "China",
        "TW" to "Taiwan",
        "HK" to "Hong Kong",
        "FR" to "France",
        "DE" to "Germany",
        "ES" to "Spain",
        "MX" to "Mexico",
        "BR" to "Brazil",
        "RU" to "Russia",
        "IT" to "Italy",
        "NL" to "Netherlands",
        "PL" to "Poland",
        "TR" to "Turkey",
        "AU" to "Australia",
        "CA" to "Canada",
        "IN" to "India",
        "ID" to "Indonesia",
        "TH" to "Thailand",
        "VN" to "Vietnam",
        "NG" to "Nigeria",
        "PH" to "Philippines",
        "MY" to "Malaysia",
        "SG" to "Singapore",
        "AR" to "Argentina",
        "CL" to "Chile",
        "CO" to "Colombia",
        "PE" to "Peru",
        "ZA" to "South Africa",
        "EG" to "Egypt",
        "SA" to "Saudi Arabia",
        "AE" to "United Arab Emirates",
    )
// Key names only the removed Canary channel ever wrote: the About easter-egg unlock flag and the
// canary release cache. The CANARY→NIGHTLY DataStore migration (see DataStore.kt) drops them; the
// names live here next to [UpdateChannel] so the migration decision below stays pure.
val CanaryLegacyKeyNames: Set<String> =
    setOf(
        "canaryChannelUnlocked",
        "canary_releases_etag",
        "canary_releases_json",
        "canary_releases_last_checked_at",
        "canary_releases_fingerprint",
    )
/**
 * What the one-time CANARY→NIGHTLY DataStore migration does for a given DataStore snapshot. The
 * removed Canary channel leaves two kinds of state behind: a stored `updateChannel` of "CANARY"
 * (rewritten to NIGHTLY — the feed those installs follow from now on) and the keys only that
 * channel wrote ([CanaryLegacyKeyNames], dropped). [needed] is false once neither remains, so the
 * migration never re-runs.
 */
data class CanaryChannelMigration(
    val channelToNightly: Boolean,
    val dropKeyNames: Set<String>,
) {
    val needed: Boolean
        get() = channelToNightly || dropKeyNames.isNotEmpty()
}
/** Pure decision for the CANARY→NIGHTLY migration, applied by DataStore.kt. */
fun canaryChannelMigration(
    storedChannel: String?,
    presentLegacyKeyNames: Set<String>,
): CanaryChannelMigration =
    CanaryChannelMigration(
        channelToNightly = storedChannel == "CANARY",
        dropKeyNames = CanaryLegacyKeyNames.intersect(presentLegacyKeyNames),
    )
const val AUDIO_PROCESSING_ENABLED_DEFAULT = true
const val BIT_PERFECT_OUTPUT_DEFAULT = false
const val BIT_PERFECT_NATIVE_RATE_DEFAULT = true
