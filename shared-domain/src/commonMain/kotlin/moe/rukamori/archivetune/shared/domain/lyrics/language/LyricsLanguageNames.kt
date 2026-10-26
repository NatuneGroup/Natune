package moe.rukamori.archivetune.shared.domain.lyrics.language

/**
 * Lookup over [LYRICS_LANGUAGE_NAME_TABLE]. The picker stores these uppercase English names, so a stored
 * exclusion and a detected language compare equal only when both come from this one table.
 */
internal object LyricsLanguageNames {
    private val byCode: Map<String, String> by lazy { parse() }
    private val byLabel: Map<String, String> by lazy { byCode.values.associateBy { it.replace('_', '-') } }

    fun nameForCode(code: String): String? = byCode[code]

    /** A label written with dashes in place of underscores, as the Android validator compared them. */
    fun canonicalNameForLabel(label: String): String? = byLabel[label]

    private fun parse(): Map<String, String> {
        val names = HashMap<String, String>()
        LYRICS_LANGUAGE_NAME_TABLE.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val parts = line.split(' ', limit = 2)
                names[parts[0]] = parts[1]
            }
        return names
    }
}
