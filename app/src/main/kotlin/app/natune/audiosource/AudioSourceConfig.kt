/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.audiosource

import app.natune.constants.AudioSourceType

/** The shared stream shape; Android providers and the playback layer use it directly. */
typealias DirectStream = app.natune.shared.domain.source.DirectStream

/**
 * Pure helpers for the multi-source framework. These operate on already-read preference values so
 * they stay free of any DataStore/Android dependencies and are trivially testable.
 */
object AudioSourceConfig {
    /**
     * Every playback source, in their built-in default priority. Spotify, Amazon and QQ sit just
     * above YouTube and default to off; YouTube stays last as the terminal fallback. Whether a
     * source can actually resolve is the enabled flags' business ([DEFAULT_ENABLED] plus the
     * sign-in state) — the order itself only ranks the sources, and a disabled one is filtered
     * out before playback ever sees it.
     */
    val DEFAULT_ORDER: List<AudioSourceType> =
        listOf(
            AudioSourceType.TIDAL,
            AudioSourceType.QOBUZ,
            AudioSourceType.QOBUZ_BACKUP,
            AudioSourceType.DEEZER,
            AudioSourceType.APPLE,
            AudioSourceType.JIOSAAVN,
            AudioSourceType.AMAZON,
            AudioSourceType.QQ,
            AudioSourceType.MODULES,
            AudioSourceType.SPOTIFY,
            AudioSourceType.YOUTUBE,
        )

    /**
     * The enabled defaults every consumer of the source flags shares (the settings UI, the
     * resolver and the download chain read this rather than carrying their own copy). Tidal and
     * YouTube are on out of the box, everything else is off. Spotify's switch additionally does
     * nothing until a streaming account is signed in, so it counts as enabled only when that is
     * true as well.
     */
    val DEFAULT_ENABLED: Map<AudioSourceType, Boolean> =
        AudioSourceType.entries.associateWith { it == AudioSourceType.TIDAL || it == AudioSourceType.YOUTUBE }

    private fun parseType(name: String): AudioSourceType? =
        runCatching { AudioSourceType.valueOf(name.trim().uppercase()) }.getOrNull()

    /**
     * Resolves the effective ordered list of ALL sources from the stored CSV, preserving the user's
     * chosen order (including where they placed YouTube) and slotting in any sources missing from
     * the stored order (e.g. after an app update introduces a new one) *above* YouTube.
     */
    fun parseOrder(rawOrder: String?): List<AudioSourceType> {
        val stored =
            rawOrder
                ?.split(',')
                ?.mapNotNull { parseType(it) }
                ?.distinct()
                .orEmpty()
        if (stored.isEmpty()) return DEFAULT_ORDER

        val missing = DEFAULT_ORDER.filterNot { it in stored }
        if (missing.isEmpty()) return stored

        val merged = mutableListOf<AudioSourceType>()
        var inserted = false
        for (source in stored) {
            if (!inserted && source == AudioSourceType.YOUTUBE) {
                merged.addAll(missing)
                inserted = true
            }
            merged.add(source)
        }
        // No YouTube in the stored order: it is one of the missing sources, and it is last in
        // DEFAULT_ORDER, so appending the whole missing block still leaves it as the final fallback.
        if (!inserted) merged.addAll(missing)
        return merged
    }

    /**
     * One-time migration of a stored order to the current source catalogue: entries the CSV has
     * never heard of are inserted directly above YouTube, where [parseOrder] would place them at
     * read time, and unknown names are dropped. Persisted so backups and the stored CSV agree
     * with what the resolver serves.
     *
     * A blank order stays blank — it keeps tracking [DEFAULT_ORDER], which is how a factory-reset
     * or restored-blank install picks up new sources without a pinned hand-written list. The
     * function is idempotent: its output always contains every [DEFAULT_ORDER] member, so running
     * it again changes nothing.
     */
    fun migrateStoredOrder(rawOrder: String?): String {
        if (rawOrder.isNullOrBlank()) return rawOrder ?: ""
        return parseOrder(rawOrder).joinToString(",") { it.name }
    }
}

/**
 * Codec for the per-song "play from" overrides. Stored as `songId=SOURCE` entries joined by `;` in a
 * single preference string so it is picked up by Settings backups. The playback layer forces the
 * chosen source for that song (still subject to the metadata match gate); YOUTUBE as an override
 * means "always use YouTube for this song".
 */
object SongSourceOverride {
    fun parse(raw: String?): Map<String, AudioSourceType> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, AudioSourceType>()
        raw.split(';').forEach { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) return@forEach
            val id = entry.substring(0, idx).trim()
            val source =
                runCatching { AudioSourceType.valueOf(entry.substring(idx + 1).trim().uppercase()) }
                    .getOrNull()
            if (id.isNotEmpty() && source != null) out[id] = source
        }
        return out
    }

    fun serialize(map: Map<String, AudioSourceType>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value.name}" }

    fun get(
        raw: String?,
        songId: String,
    ): AudioSourceType? = parse(raw)[songId]

    /** Returns the updated raw string with [songId] set to [source], or cleared when [source] is null. */
    fun withOverride(
        raw: String?,
        songId: String,
        source: AudioSourceType?,
    ): String {
        val map = LinkedHashMap(parse(raw))
        if (source == null) map.remove(songId) else map[songId] = source
        return serialize(map)
    }
}

/**
 * Codec for per-song Qobuz trackId overrides. Same CSV shape as [SongSourceOverride] but maps
 * `songId → qobuzTrackId` instead of source.
 */
object SongSourceQobuzTrackId {
    fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split(';').forEach { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) return@forEach
            val id = entry.substring(0, idx).trim()
            val trackId = entry.substring(idx + 1).trim()
            if (id.isNotEmpty() && trackId.isNotEmpty()) out[id] = trackId
        }
        return out
    }

    fun serialize(map: Map<String, String>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun get(
        raw: String?,
        songId: String,
    ): String? = parse(raw)[songId]

    /** Returns the updated raw string with [songId] set to [trackId], or cleared when [trackId] is null. */
    fun withOverride(
        raw: String?,
        songId: String,
        trackId: String?,
    ): String {
        val map = LinkedHashMap(parse(raw))
        if (trackId == null) map.remove(songId) else map[songId] = trackId
        return serialize(map)
    }
}

/** Codec for per-song Qobuz-backup video-id overrides. */
object SongSourceQobuzBackupVideoId {
    fun parse(raw: String?): Map<String, String> = SongSourceQobuzTrackId.parse(raw)

    fun serialize(map: Map<String, String>): String = SongSourceQobuzTrackId.serialize(map)

    fun get(
        raw: String?,
        songId: String,
    ): String? = SongSourceQobuzTrackId.get(raw, songId)

    /** Returns the updated raw string with [songId] set to [videoId], or cleared when [videoId] is null. */
    fun withOverride(
        raw: String?,
        songId: String,
        videoId: String?,
    ): String = SongSourceQobuzTrackId.withOverride(raw, songId, videoId)
}

data class CurrentStreamInfo(
    val mediaId: String,
    val source: AudioSourceType,
    val label: String,
    val protocol: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
)
