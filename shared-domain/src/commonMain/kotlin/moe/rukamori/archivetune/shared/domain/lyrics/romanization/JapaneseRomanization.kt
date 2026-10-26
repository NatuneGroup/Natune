package moe.rukamori.archivetune.shared.domain.lyrics.romanization

/**
 * The reading used for romanisation: the dictionary reading, or the surface when the dictionary has none
 * (empty or "*" placeholder).
 */
private fun JapaneseToken.readingOrSurface(): String = reading?.takeIf { it.isNotEmpty() && it != "*" } ?: surface

/** Romanises a whole line one token at a time, with sokuon at a token boundary looking at the next token. */
internal fun romanizeJapaneseWithTokenizer(
    text: String,
    tokenizer: JapaneseTokenizer,
): String {
    val tokens = tokenizer.tokenize(text)
    return tokens
        .mapIndexed { index, token ->
            val katakanaReading = hiraganaToKatakana(token.readingOrSurface())
            val nextTokenReading = tokens.getOrNull(index + 1)?.let { hiraganaToKatakana(it.readingOrSurface()) }
            katakanaToRomaji(katakanaReading, nextTokenReading)
        }.joinToString(" ")
}

/**
 * Per-word romaji for one Japanese line. A word gets a reading only when every token it touches lies
 * wholly inside it, because no kana-to-fragment alignment inside a token is trustworthy. The whole result
 * is null-filled when any word is missing from the line or any token straddles a word boundary.
 */
internal fun romanizeJapaneseWordsForLine(
    words: List<String>,
    lineText: String,
    tokenizer: JapaneseTokenizer?,
): List<String?> {
    if (words.isEmpty()) return emptyList()
    if (tokenizer == null) return words.map { null }

    val tokens = tokenizer.tokenize(lineText)
    if (tokens.isEmpty()) return words.map { null }

    val tokenCount = tokens.size
    val tokenStarts = IntArray(tokenCount)
    val tokenEnds = IntArray(tokenCount)
    val tokenRomaji = ArrayList<String>(tokenCount)
    for (i in 0 until tokenCount) {
        val token = tokens[i]
        tokenStarts[i] = token.position
        tokenEnds[i] = token.position + token.surface.length
        val katakanaReading = hiraganaToKatakana(token.readingOrSurface())
        val nextTokenReading = tokens.getOrNull(i + 1)?.let { hiraganaToKatakana(it.readingOrSurface()) }
        tokenRomaji.add(katakanaToRomaji(katakanaReading, nextTokenReading))
    }

    // Words and tokens are both in text order, so one forward pass is enough.
    val result = ArrayList<String?>(words.size)
    var scanOffset = 0
    var tokenIdx = 0
    for (word in words) {
        val wordStart = lineText.indexOf(word, startIndex = scanOffset)
        if (wordStart < 0) return words.map { null }
        val wordEnd = wordStart + word.length

        while (tokenIdx < tokenCount && tokenEnds[tokenIdx] <= wordStart) {
            tokenIdx++
        }

        val romajiBuilder = StringBuilder()
        var tIdx = tokenIdx
        while (tIdx < tokenCount && tokenStarts[tIdx] < wordEnd) {
            if (tokenEnds[tIdx] > wordStart) {
                if (tokenStarts[tIdx] < wordStart || tokenEnds[tIdx] > wordEnd) {
                    return words.map { null }
                }
                romajiBuilder.append(tokenRomaji[tIdx])
            }
            tIdx++
        }

        result.add(romajiBuilder.toString().takeIf { it.isNotEmpty() })
        scanOffset = wordEnd
    }
    return result
}
