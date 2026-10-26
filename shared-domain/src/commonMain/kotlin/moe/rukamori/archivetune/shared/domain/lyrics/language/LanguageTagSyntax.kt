package moe.rukamori.archivetune.shared.domain.lyrics.language

/**
 * Hand-rolled BCP 47 well-formedness check that stands in for `Locale.Builder.setLanguageTag`, which the
 * Android hint validator relied on. That builder is strict: an ill-formed tag throws, and that throw is the
 * only thing that rejects a hint such as "not a tag". The lyrics policy reads just the primary language and
 * the script, so every other subtag is checked for shape and then discarded.
 */
internal object LanguageTagSyntax {
    internal class Parsed(
        val language: String,
        val script: String?,
    )

    private const val MAX_EXTLANGS = 3

    fun parse(tag: String): Parsed? {
        val subtags = tag.split('-')
        if (subtags.any { !isShapeOk(it) }) return null
        val first = subtags[0]
        if (!first.isAsciiAlpha() || (first.length !in 2..3 && first.length !in 5..8)) return null

        var language = first.lowercase()
        var index = 1
        // An extlang replaces the primary language ("zh-yue" reads as "yue"), so it has to be read first.
        if (first.length in 2..3) {
            var extlangs = 0
            while (index < subtags.size && extlangs < MAX_EXTLANGS && subtags[index].length == 3 && subtags[index].isAsciiAlpha()) {
                if (extlangs == 0) language = subtags[index].lowercase()
                extlangs++
                index++
            }
        }
        var script: String? = null
        if (index < subtags.size && subtags[index].length == 4 && subtags[index].isAsciiAlpha()) {
            script = subtags[index]
            index++
        }
        if (index < subtags.size && isRegion(subtags[index])) index++
        while (index < subtags.size && isVariant(subtags[index])) index++
        while (index < subtags.size && subtags[index].length == 1 && !isPrivateUseSingleton(subtags[index])) {
            index++
            var count = 0
            while (index < subtags.size && subtags[index].length in 2..8) {
                index++
                count++
            }
            if (count == 0) return null
        }
        if (index < subtags.size && isPrivateUseSingleton(subtags[index])) {
            index++
            var count = 0
            while (index < subtags.size && subtags[index].length in 1..8) {
                index++
                count++
            }
            if (count == 0) return null
        }
        if (index != subtags.size) return null
        return Parsed(language, script)
    }

    private fun isShapeOk(subtag: String): Boolean =
        subtag.length in 1..8 && subtag.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    private fun isRegion(subtag: String): Boolean =
        (subtag.length == 2 && subtag.isAsciiAlpha()) || (subtag.length == 3 && subtag.all { it in '0'..'9' })

    private fun isVariant(subtag: String): Boolean =
        subtag.length in 5..8 || (subtag.length == 4 && subtag[0] in '0'..'9')

    private fun isPrivateUseSingleton(subtag: String): Boolean = subtag.equals("x", ignoreCase = true)

    private fun String.isAsciiAlpha(): Boolean = all { it in 'a'..'z' || it in 'A'..'Z' }
}
