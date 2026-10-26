package moe.rukamori.archivetune.shared.domain.settings

/** Separators the baseline bulk-add parser accepts: any whitespace, commas and semicolons. */
private val SourceInstanceSeparators = Regex("[\\s,;]+")

/**
 * Splits pasted text into candidate instance URLs in the order they were pasted. Surrounding quotes and brackets are
 * stripped, https is added where no scheme is given, entries without a dotted host are dropped, and case-insensitive
 * duplicates are removed. Shared by the Tidal and Qobuz instance managers, as the baseline's SourceInputParsing is.
 */
fun parseSourceInstanceUrls(input: String): List<String> {
    if (input.isBlank()) return emptyList()
    val seen = LinkedHashSet<String>()
    return input
        .split(SourceInstanceSeparators)
        .asSequence()
        .map { it.trim().trim('"', '\'', '<', '>', '(', ')', ',') }
        .filter { it.isNotEmpty() }
        .mapNotNull(::candidateSourceUrl)
        .filter { seen.add(it.lowercase()) }
        .toList()
}

private fun candidateSourceUrl(raw: String): String? {
    val url = if (raw.contains("://")) raw else "https://$raw"
    if (!url.substringAfter("://").substringBefore('/').contains('.')) return null
    return url.trimEnd('/')
}

/**
 * Validates one instance base URL for the managers' add and bulk-add paths, matching the baseline acceptance of
 * `toHttpUrlOrNull()` followed by its dot-in-host rule. The entry is returned as typed, without trailing slashes and
 * with https added when no scheme is given. A path, query, fragment or userinfo is kept as the baseline keeps it.
 * Host characters that OkHttp rejects, a missing or out-of-range port, and an IPv6 literal are rejected. This is a
 * pure check, so it reproduces OkHttp's observable result for ordinary and IDN hosts without a full RFC 3986 parser.
 */
fun normalizeSourceInstanceUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    val authority = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val hostPort = authority.substringAfterLast('@')
    if (hostPort.startsWith('[')) return null
    val colonCount = hostPort.count { it == ':' }
    if (colonCount > 1) return null
    val host = hostPort.substringBefore(':')
    if (host.isEmpty() || !host.contains('.') || host.any(::isForbiddenHostCharacter)) return null
    if (colonCount == 1) {
        val port = hostPort.substringAfter(':').toIntOrNull() ?: return null
        if (port !in 1..65535) return null
    }
    return withScheme
}

/** Characters OkHttp refuses in a canonical host: controls, DEL, space and the URL delimiters. */
private fun isForbiddenHostCharacter(c: Char): Boolean =
    c.code <= 0x1F || c.code == 0x7F || c in " #%/:?@[\\]"

/** Outcome of the host's last probe of one proxy instance or account token, shared by the Qobuz and Tidal managers. */
enum class SourceHealthStatus { HEALTHY, PREVIEW_ONLY, UNREACHABLE }

/** Latest probe result. [latencyMs] is null when the probe did not measure a round trip. */
data class SourceHealth(val status: SourceHealthStatus, val latencyMs: Long? = null)
