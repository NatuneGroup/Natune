/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

/** Devanagari romanisation tables from the Android Hindi mapper. The mapper that reads them is in HindiRomanization.kt. */
internal val DEVANAGARI_INDEPENDENT_VOWELS =
    mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ii", 'उ' to "u", 'ऊ' to "uu",
        'ऋ' to "ri", 'ॠ' to "rii", 'ऌ' to "lri", 'ॡ' to "lrii",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
    )

internal val DEVANAGARI_MATRAS =
    mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ii", 'ु' to "u", 'ू' to "uu",
        'ृ' to "ri", 'ॄ' to "rii", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
        'ॅ' to "e", 'ॉ' to "o", 'ॢ' to "lri", 'ॣ' to "lrii",
    )

internal val DEVANAGARI_CONSONANTS =
    mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'ळ' to "l",
        // Note: common conjuncts (क्ष, ज्ञ, त्र, श्र) are NOT single Unicode
        // codepoints — they're consonant + virama + consonant sequences, so they
        // can't be map keys here. They are handled naturally by the main loop's
        // consonant + virama + consonant flow, which produces the same result
        // (e.g. क + ् + ष → "k" + "" + "sh" + "a" = "ksha").
    )

// Approximations for less-common letters / chillu characters (Malayalam-in-Devanagari etc.)
internal val DEVANAGARI_OTHER =
    mapOf(
        'ॐ' to "om",
        '।' to ".", '॥' to "..",
        'ऽ' to "'",  // avagraha
        'ं' to "n",  // anusvara (default; refined by context below)
        'ः' to "h",  // visarga
        'ँ' to "n",  // candrabindu (nasalization, simplified)
        '्' to "",   // virama (halant) — suppresses inherent "a"; handled by loop
    )

internal val DEVANAGARI_NUMERALS =
    mapOf(
        '०' to "0", '१' to "1", '२' to "2", '३' to "3", '४' to "4",
        '५' to "5", '६' to "6", '७' to "7", '८' to "8", '९' to "9",
    )

internal val LABIALS = setOf('प', 'फ', 'ब', 'भ', 'म')
