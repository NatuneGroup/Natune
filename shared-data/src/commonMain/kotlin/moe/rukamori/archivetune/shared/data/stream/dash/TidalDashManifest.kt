package moe.rukamori.archivetune.shared.data.stream.dash

/**
 * One TIDAL MPEG-DASH manifest reduced to what the native path needs: the ordered segment URLs (the initialization
 * segment first when the manifest declares one) and the representation's MIME type and codecs.
 *
 * The rules mirror the retained Android parser, including its FLAC default for a manifest that states no codecs.
 * Both platforms therefore choose the same route for the same manifest, and a codec-less manifest is treated as FLAC.
 */
class TidalDashManifest private constructor(
    /** Ordered absolute segment URLs. A single entry is a full file named only by BaseURL, not a segmented stream. */
    val segmentUrls: List<String>,
    val mimeType: String,
    val codecs: String,
) {
    /** The retained route test: either the MIME type or the codecs name FLAC. */
    val isFlac: Boolean
        get() = mimeType.contains("flac", ignoreCase = true) || codecs.contains("flac", ignoreCase = true)

    companion object {
        private const val DEFAULT_MIME_TYPE = "audio/mp4"
        private const val DEFAULT_CODECS = "flac"

        // A bound on the segment count. A long track at a few seconds per segment is far below it, and the bound stops
        // a malformed repeat count from allocating an unbounded URL list.
        private const val MAX_SEGMENTS = 20_000

        private val TIMELINE_SEGMENT_REGEX =
            Regex("""<S\s+[^>]*d="(\d+)"(?:\s+r="(-?\d+)")?[^>]*/?>""", RegexOption.IGNORE_CASE)
        private val INITIALIZATION_URL_REGEX =
            Regex("<Initialization\\b[^>]*sourceURL=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        private val SEGMENT_URL_REGEX = Regex("<SegmentURL\\b[^>]*media=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

        /**
         * Parses [text], or returns null when it declares no segment element, lists no usable segment, or exceeds the
         * segment bound. Malformed input never throws, so the caller can turn null into a refusal.
         */
        fun parse(text: String): TidalDashManifest? {
            if (!hasSegmentElement(text)) return null
            val baseUrl = firstTagValue(text, "BaseURL").xmlUnescape()
            val segments = extractSegmentUrls(text, baseUrl) ?: return null
            if (segments.isEmpty() || segments.size > MAX_SEGMENTS) return null
            return TidalDashManifest(
                segmentUrls = segments,
                mimeType = firstAttribute(text, "mimeType").ifBlank { DEFAULT_MIME_TYPE },
                codecs = firstAttribute(text, "codecs").ifBlank { DEFAULT_CODECS },
            )
        }

        /** The same four markers the retained parser checks before it trusts the document. */
        private fun hasSegmentElement(text: String): Boolean =
            listOf("<SegmentTemplate", "<SegmentList", "<SegmentBase", "<BaseURL").any { marker ->
                text.contains(marker, ignoreCase = true)
            }

        /**
         * Segment addressing in the retained order: an explicit SegmentList first, then a SegmentTemplate with a
         * SegmentTimeline, then a bare BaseURL. A template without both initialization and media is a single file.
         */
        private fun extractSegmentUrls(text: String, baseUrl: String): List<String>? {
            listUrls(text, baseUrl).takeIf { it.isNotEmpty() }?.let { return it }

            val initialization = firstAttribute(text, "initialization").xmlUnescape()
            val mediaTemplate = firstAttribute(text, "media").xmlUnescape()
            if (initialization.isBlank() || mediaTemplate.isBlank()) {
                return baseUrl.takeIf { isAbsoluteHttp(it) }?.let { listOf(it) }
            }

            // Each timeline entry stands for repeat+1 segments. A negative repeat is counted as one segment, as the
            // retained parser does; the bound below stops a huge repeat from allocating an unbounded list.
            var segmentCount = 0L
            for (match in TIMELINE_SEGMENT_REGEX.findAll(text)) {
                val repeat = match.groupValues.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: 0
                segmentCount += repeat + 1
                if (segmentCount > MAX_SEGMENTS) return null
            }
            if (segmentCount <= 0L) return null

            return buildList {
                add(resolveUrl(baseUrl, initialization))
                for (index in 1..segmentCount.toInt()) {
                    add(resolveUrl(baseUrl, mediaTemplate.replace("\$Number\$", index.toString())))
                }
            }
        }

        /** Explicit SegmentList addressing: an optional initialization followed by each SegmentURL in document order. */
        private fun listUrls(text: String, baseUrl: String): List<String> {
            val initialization =
                INITIALIZATION_URL_REGEX.find(text)?.groupValues?.getOrNull(1)?.xmlUnescape()
            val media =
                SEGMENT_URL_REGEX.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.xmlUnescape() }.toList()
            return buildList {
                initialization?.let { add(resolveUrl(baseUrl, it)) }
                media.forEach { add(resolveUrl(baseUrl, it)) }
            }
        }

        /** Relative segment paths resolve against the BaseURL only when that base is absolute http(s). */
        internal fun resolveUrl(baseUrl: String, path: String): String {
            if (isAbsoluteHttp(path)) return path
            if (baseUrl.isBlank() || !isAbsoluteHttp(baseUrl)) return path
            return resolveReference(baseUrl, path) ?: (baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
        }

        private fun isAbsoluteHttp(url: String): Boolean =
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

        /**
         * RFC 3986 reference resolution for the forms a DASH manifest uses: network-path, absolute-path, query and
         * relative-path references. Returns null when the base has no scheme separator.
         */
        private fun resolveReference(base: String, reference: String): String? {
            val schemeEnd = base.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = base.substring(0, schemeEnd)
            val afterScheme = base.substring(schemeEnd + 3).substringBefore('#')
            val authorityEnd =
                afterScheme.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) afterScheme.length else it }
            val authority = afterScheme.substring(0, authorityEnd)
            val basePath = afterScheme.substring(authorityEnd).substringBefore('?').ifEmpty { "/" }

            val ref = reference.substringBefore('#')
            if (ref.startsWith("//")) return "$scheme:$ref"
            val refPath = ref.substringBefore('?')
            val query = if (ref.contains('?')) ref.substringAfter('?') else null
            val path =
                when {
                    refPath.isEmpty() -> basePath
                    refPath.startsWith("/") -> refPath
                    else -> basePath.substringBeforeLast('/', "") + "/" + refPath
                }
            return "$scheme://$authority${removeDotSegments(path)}" + (if (query != null) "?$query" else "")
        }

        /**
         * RFC 3986 dot-segment removal: "." disappears and ".." pops the previous kept segment. A path that ends on a
         * dot segment keeps its trailing slash, and a rooted path that collapses to nothing stays "/".
         */
        private fun removeDotSegments(path: String): String {
            if (path.isEmpty()) return path
            val rooted = path.startsWith("/")
            val segments = path.split('/')
            val kept = ArrayList<String>()
            var trailingSlash = false
            for ((index, segment) in segments.withIndex()) {
                if (index == 0 && rooted) continue
                val last = index == segments.lastIndex
                trailingSlash = false
                when (segment) {
                    "." -> trailingSlash = last
                    ".." -> {
                        if (kept.isNotEmpty()) kept.removeAt(kept.lastIndex)
                        trailingSlash = last
                    }
                    else -> kept.add(segment)
                }
            }
            val body = kept.joinToString("/")
            if (body.isEmpty()) return if (rooted) "/" else ""
            return (if (rooted) "/" else "") + body + (if (trailingSlash && !body.endsWith("/")) "/" else "")
        }

        /** Reads a quoted attribute anywhere in the document, first match wins, as the retained parser does. */
        private fun firstAttribute(text: String, attribute: String): String =
            Regex("""\b${Regex.escape(attribute)}\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

        /** Text content of the first element with [tag], trimmed, so a BaseURL with surrounding whitespace still resolves. */
        private fun firstTagValue(text: String, tag: String): String =
            Regex(
                """<${Regex.escape(tag)}\b[^>]*>([\s\S]*?)</${Regex.escape(tag)}>""",
                RegexOption.IGNORE_CASE,
            ).find(text)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
                .trim()

        /** Only the five named entities the retained parser decodes; numeric references stay as written. */
        private fun String.xmlUnescape(): String =
            replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
    }
}
