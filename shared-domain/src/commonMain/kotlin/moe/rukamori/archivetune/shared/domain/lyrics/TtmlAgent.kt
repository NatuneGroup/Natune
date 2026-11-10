package moe.rukamori.archivetune.shared.domain.lyrics

/** Agent every renderer centres: a line that more than one voice sings together. */
const val TTML_GROUP_AGENT: String = "group"

private val ttmlIdRefSeparator = Regex("\\s+")

/**
 * `ttm:agent` is an IDREFS list, so a duet line can name several agents ("v1 v2"), and some
 * providers write each reference as a fragment ("#v2"). The renderers key on one bare id, so the
 * list collapses to that id when only one distinct agent remains, to [TTML_GROUP_AGENT] when several
 * do, and to null when the attribute names none.
 */
fun normalizeTtmlAgent(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val ids =
        raw.trim()
            .split(ttmlIdRefSeparator)
            .map { it.removePrefix("#") }
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
    return when (ids.size) {
        0 -> null
        1 -> ids.single()
        else -> TTML_GROUP_AGENT
    }
}
