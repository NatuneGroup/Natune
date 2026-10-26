/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

/**
 * A hand-written Devanagari to Latin mapper that aims for pronounceable romanisation, for example
 * "नमस्ते" to "namaste". The inherent vowel of a consonant is dropped when a matra, virama or
 * anusvara follows it, and anusvara takes the labial nasal "m" before a labial.
 */
fun romanizeHindi(text: String): String {
    val sb = StringBuilder(text.length * 2)
    var i = 0
    val n = text.length
    while (i < n) {
        val ch = text[i]

        val numeral = DEVANAGARI_NUMERALS[ch]
        if (numeral != null) {
            sb.append(numeral)
            i++
            continue
        }

        // Anusvara is "m" before a labial and "n" otherwise.
        if (ch == 'ं') {
            val next = text.getOrNull(i + 1)
            sb.append(if (next != null && next in LABIALS) "m" else "n")
            i++
            continue
        }

        if (ch == 'ँ') {
            sb.append("n")
            i++
            continue
        }

        if (ch == 'ः') {
            sb.append("h")
            i++
            continue
        }

        // Virama only suppresses the inherent vowel, which the consonant branch already handled.
        if (ch == '्') {
            i++
            continue
        }

        // A matra replaces the inherent "a" of the consonant before it; that consonant was emitted without one.
        val matra = DEVANAGARI_MATRAS[ch]
        if (matra != null) {
            sb.append(matra)
            i++
            continue
        }

        val independentVowel = DEVANAGARI_INDEPENDENT_VOWELS[ch]
        if (independentVowel != null) {
            sb.append(independentVowel)
            i++
            continue
        }

        // A consonant keeps its inherent "a" unless the next character is a sign that replaces or
        // suppresses it.
        val consonant = DEVANAGARI_CONSONANTS[ch]
        if (consonant != null) {
            val next = text.getOrNull(i + 1)
            val suppressInherentA =
                next != null &&
                    (
                        next in DEVANAGARI_MATRAS ||
                            next == '्' ||
                            next == 'ं' ||
                            next == 'ः' ||
                            next == 'ँ'
                    )
            sb.append(consonant)
            if (!suppressInherentA) {
                sb.append('a')
            }
            i++
            continue
        }

        val other = DEVANAGARI_OTHER[ch]
        if (other != null) {
            sb.append(other)
            i++
            continue
        }

        // Latin letters, spaces and punctuation pass through as they are.
        sb.append(ch)
        i++
    }
    return sb.toString()
}
