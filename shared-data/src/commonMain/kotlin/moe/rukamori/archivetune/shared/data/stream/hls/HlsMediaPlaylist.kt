package moe.rukamori.archivetune.shared.data.stream.hls

import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin

/** One `#EXT-X-BYTERANGE` extent inside a segment resource. A null [offset] continues the previous range. */
internal class HlsByteRange(
    val length: Long,
    val offset: Long?,
)

/**
 * One `#EXT-X-MAP` init section: the resource URI, resolved, an optional byte range inside it, and the key
 * state active when the tag was declared. [keyIndex] is the index into [HlsMediaPlaylist.keys], or -1 when the
 * section is clear; the specification requires an explicit IV for an encrypted init section, which the parser
 * enforces at declaration.
 */
internal class HlsInitMap(
    val uri: String,
    val byteRange: HlsByteRange?,
    val keyIndex: Int,
)

/**
 * One media segment. [uri] is already resolved against the playlist URL. [keyIndex] is the index of the active
 * `#EXT-X-KEY` entry in [HlsMediaPlaylist.keys], or -1 when the segment is clear. [initIndex] is the index of the
 * active `#EXT-X-MAP` entry in [HlsMediaPlaylist.initMaps], or -1 when none applies. [mediaSequence] counts from the
 * playlist's `#EXT-X-MEDIA-SEQUENCE` base and derives the AES-128 IV when the key tag states none.
 */
internal class HlsSegment(
    val uri: String,
    val byteRange: HlsByteRange?,
    val keyIndex: Int,
    val initIndex: Int,
    val mediaSequence: Long,
    val discontinuity: Boolean,
)

/** A parsed VOD media playlist. Every URI is resolved against the playlist URL and verified as HTTP(S). */
internal class HlsMediaPlaylist(
    val initMaps: List<HlsInitMap>,
    val segments: List<HlsSegment>,
    val keys: List<HlsKeyTag>,
)

/** A playlist-level refusal: the document is not a media playlist this host can honour. Never carries a URL. */
internal class HlsPlaylistException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Parses an HLS media playlist (RFC 8216). Only the non-DRM forms are accepted: clear segments and AES-128 segments
 * whose key format is the default `identity`. Variant or master playlists, live or event playlists without
 * `#EXT-X-ENDLIST`, `SAMPLE-AES` and every non-identity `KEYFORMAT` (Apple FairPlay, PlayReady, Widevine UUIDs) are
 * refused rather than decoded, because those are either unbounded or need a platform content-decryption module.
 * Unknown tags are tolerated, as forward-compatible playlists must be.
 */
internal object HlsMediaPlaylistParser {
    fun parse(
        playlistUrl: String,
        text: String,
    ): HlsMediaPlaylist {
        if (text.length > MAX_PLAYLIST_CHARS) {
            throw HlsPlaylistException("the HLS playlist exceeds its size bound")
        }
        var seenHeader = false
        var mediaSequenceBase = 0L
        var endList = false
        var keyIndex = -1
        var initIndex = -1
        var pendingRange: HlsByteRange? = null
        var pendingDiscontinuity = false
        val keys = ArrayList<HlsKeyTag>()
        val initMaps = ArrayList<HlsInitMap>()
        val segments = ArrayList<HlsSegment>()

        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!seenHeader) {
                if (line != EXTM3U) throw HlsPlaylistException("the document is not an HLS playlist")
                seenHeader = true
                continue
            }
            when {
                line == "#EXT-X-ENDLIST" -> endList = true
                line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF") ->
                    throw HlsPlaylistException("an HLS variant playlist is not segmented media")
                line.startsWith("#EXT-X-MEDIA:") -> {
                    // A rendition URI makes this a master playlist; a URI-less media tag only describes the stream.
                    if (tagAttributes(line)["URI"] != null) {
                        throw HlsPlaylistException("an HLS variant playlist is not segmented media")
                    }
                }
                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    val attributes = tagAttributes(line)
                    when (attributes["METHOD"]) {
                        "NONE" -> keyIndex = -1
                        "AES-128" -> {
                            val keyFormat = attributes["KEYFORMAT"]
                            if (keyFormat != null && keyFormat != IDENTITY_KEY_FORMAT) {
                                throw HlsPlaylistException(
                                    "HLS key format $keyFormat needs a platform content-decryption module",
                                )
                            }
                            val tag =
                                try {
                                    HlsAes128.parseKeyTag(line)
                                } catch (invalid: Throwable) {
                                    throw HlsPlaylistException("the HLS key tag is malformed", invalid)
                                } ?: throw HlsPlaylistException("the HLS AES-128 key tag has no key URI")
                            // Key URIs resolve against the playlist like segment and map URIs, so every
                            // resource the playlist names is already absolute and HTTP(S) here.
                            val resolvedKey =
                                resolveHlsUri(playlistUrl, tag.keyUri)
                                    ?: throw HlsPlaylistException("an HLS key is not an HTTP(S) resource")
                            keys.add(HlsKeyTag(resolvedKey, tag.iv))
                            if (keys.size > MAX_KEYS) {
                                throw HlsPlaylistException("the playlist rotates keys beyond its bound")
                            }
                            keyIndex = keys.lastIndex
                        }
                        else ->
                            throw HlsPlaylistException(
                                "HLS method ${attributes["METHOD"] ?: "?"} needs a platform content-decryption module",
                            )
                    }
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequenceBase =
                        line.substringAfter(':').trim().toLongOrNull()
                            ?: throw HlsPlaylistException("the HLS media sequence is malformed")
                }
                line.startsWith("#EXT-X-BYTERANGE:") ->
                    pendingRange = parseByteRange(line.substringAfter(':'))
                line.startsWith("#EXT-X-MAP:") -> {
                    val attributes = tagAttributes(line)
                    val uri =
                        attributes["URI"] ?: throw HlsPlaylistException("an HLS map tag has no URI")
                    val resolved =
                        resolveHlsUri(playlistUrl, uri)
                            ?: throw HlsPlaylistException("an HLS map is not an HTTP(S) resource")
                    // An encrypted init section has no media sequence to derive an IV from, so the
                    // specification requires the key tag's explicit IV for it.
                    if (keyIndex >= 0 && keys[keyIndex].iv == null) {
                        throw HlsPlaylistException("an encrypted HLS init section needs an explicit IV")
                    }
                    initMaps.add(HlsInitMap(resolved, attributes["BYTERANGE"]?.let(::parseByteRange), keyIndex))
                    if (initMaps.size > MAX_INIT_MAPS) {
                        throw HlsPlaylistException("the playlist declares too many init maps")
                    }
                    initIndex = initMaps.lastIndex
                }
                line.startsWith("#EXT-X-DISCONTINUITY") -> pendingDiscontinuity = true
                line.startsWith("#") -> Unit
                else -> {
                    val resolved =
                        resolveHlsUri(playlistUrl, line)
                            ?: throw HlsPlaylistException("an HLS segment is not an HTTP(S) resource")
                    segments.add(
                        HlsSegment(
                            uri = resolved,
                            byteRange = pendingRange,
                            keyIndex = keyIndex,
                            initIndex = initIndex,
                            mediaSequence = mediaSequenceBase + segments.size,
                            discontinuity = pendingDiscontinuity,
                        ),
                    )
                    if (segments.size > MAX_SEGMENTS) {
                        throw HlsPlaylistException("the playlist lists more segments than its bound")
                    }
                    pendingRange = null
                    pendingDiscontinuity = false
                }
            }
        }
        if (!seenHeader) throw HlsPlaylistException("the document is not an HLS playlist")
        if (!endList) throw HlsPlaylistException("a live or unbounded HLS playlist is not playable here")
        if (segments.isEmpty()) throw HlsPlaylistException("the playlist lists no segments")
        return HlsMediaPlaylist(initMaps, segments, keys)
    }

    /** The attribute map of a `#NAME:attrs` tag line, or an empty map for a bare `#NAME`. */
    private fun tagAttributes(line: String): Map<String, String> {
        val colon = line.indexOf(':')
        if (colon < 0) return emptyMap()
        return HlsAes128.parseAttributes(line.substring(colon + 1))
    }

    /** Parses `n` or `n@o` of `#EXT-X-BYTERANGE`, also used inside `#EXT-X-MAP`. */
    private fun parseByteRange(text: String): HlsByteRange {
        val trimmed = text.trim().trim('"')
        val parts = trimmed.split('@')
        if (parts.size > 2) throw HlsPlaylistException("an HLS byte range is malformed")
        val length = parts[0].toLongOrNull()?.takeIf { it > 0 }
            ?: throw HlsPlaylistException("an HLS byte range is malformed")
        val offset =
            if (parts.size == 2) {
                parts[1].toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw HlsPlaylistException("an HLS byte range is malformed")
            } else {
                null
            }
        return HlsByteRange(length, offset)
    }

    private const val EXTM3U = "#EXTM3U"
    private const val IDENTITY_KEY_FORMAT = "identity"
    private const val MAX_PLAYLIST_CHARS = 1 shl 20
    private const val MAX_SEGMENTS = 4096
    private const val MAX_KEYS = 64
    private const val MAX_INIT_MAPS = 16
}

/** True when [url] names an HLS playlist resource: its path ends in `.m3u8`, ignoring query and fragment. */
internal fun isHlsPlaylistUrl(url: String): Boolean =
    url.substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8")

/**
 * Resolves [reference] against [base] per RFC 8216 URI rules and returns the absolute HTTP(S) URL, or null when the
 * result is not HTTP(S) or the reference is not a URI. Only http and https are produced: `skd:`, `data:` and every
 * other scheme refuse, so no segment or key can be fetched outside the lease transport.
 */
internal fun resolveHlsUri(
    base: String,
    reference: String,
): String? {
    val ref = reference.trim()
    if (ref.isEmpty() || ref.any { it == '\u0000' || it.isISOControl() }) return null
    if (ref.startsWith("https://") || ref.startsWith("http://")) return ref
    // An absolute URI whose scheme is not HTTP(S) -- skd:, data: and the like -- is refused here rather than
    // resolved as a relative path, so it can never name a fetchable segment or key.
    val colon = ref.indexOf(':')
    if (colon > 0 && ref[0].isLetter() &&
        ref.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    ) {
        return null
    }
    val schemeEnd = base.indexOf("://")
    if (schemeEnd < 0) return null
    val scheme = base.substring(0, schemeEnd)
    if (scheme != "https" && scheme != "http") return null
    val authority = base.substring(schemeEnd + 3).substringBefore('/')
    if (authority.isEmpty()) return null
    val joined =
        when {
            ref.startsWith("//") -> "$scheme:$ref"
            ref.startsWith("/") -> "$scheme://$authority$ref"
            else -> {
                val basePath = base.substringBefore('?').substringBefore('#')
                val dirEnd = basePath.lastIndexOf('/')
                val directory =
                    if (dirEnd > schemeEnd + 2) basePath.substring(0, dirEnd + 1) else "$scheme://$authority/"
                directory + ref
            }
        }
    return removeDotSegments(joined)
}

/**
 * Collapses `.` and `..` path segments of an absolute URL, keeping scheme and authority intact. Only the path is
 * normalised: the query and fragment are cut off first and reattached byte-identical, because a dot inside a
 * query is data, not a path segment.
 */
private fun removeDotSegments(url: String): String {
    val tailStart =
        listOf(url.indexOf('?'), url.indexOf('#')).filter { it >= 0 }.minOrNull() ?: url.length
    val head = url.substring(0, tailStart)
    val tail = url.substring(tailStart)
    val schemeEnd = head.indexOf("://")
    if (schemeEnd < 0) return url
    val afterAuthority = head.substring(schemeEnd + 3)
    val firstSlash = afterAuthority.indexOf('/')
    if (firstSlash < 0) return url
    val prefix = head.substring(0, schemeEnd + 3 + firstSlash)
    val parts = ArrayList<String>()
    for (part in afterAuthority.substring(firstSlash).split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
    }
    return prefix + "/" + parts.joinToString("/") + tail
}

/** The greatest count of distinct origins one playlist may name; more is a resource fan-out the lease must refuse. */
internal const val MAX_HLS_RESOURCE_ORIGINS = 256

/**
 * Every distinct origin one parsed playlist names — the playlist document itself, its segments, init sections and
 * key URIs — in declaration order. Preparation sizes the lease from this set and the view refuses a resource whose
 * origin is outside it. Any URI that is not HTTP(S) or a fan-out beyond [MAX_HLS_RESOURCE_ORIGINS] throws.
 */
internal fun hlsPlaylistOrigins(
    playlistUrl: String,
    playlist: HlsMediaPlaylist,
): Set<StreamOrigin> {
    val origins = LinkedHashSet<StreamOrigin>()
    fun admit(url: String) {
        origins.add(
            StreamOrigin.parse(url) ?: throw HlsPlaylistException("an HLS resource is not an HTTP(S) URL"),
        )
        if (origins.size > MAX_HLS_RESOURCE_ORIGINS) {
            throw HlsPlaylistException("the HLS playlist spans more origins than the lease bound")
        }
    }
    admit(playlistUrl)
    playlist.segments.forEach { admit(it.uri) }
    playlist.initMaps.forEach { admit(it.uri) }
    playlist.keys.forEach { admit(it.keyUri) }
    return origins
}
