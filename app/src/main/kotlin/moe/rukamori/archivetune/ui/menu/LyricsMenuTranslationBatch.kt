package moe.rukamori.archivetune.ui.menu

import moe.rukamori.archivetune.ai.AiLyricsSegment
import java.util.UUID

internal fun List<AiLyricsSegment>.chunkedForTranslator(): List<List<AiLyricsSegment>> {
    val chunks = ArrayList<List<AiLyricsSegment>>()
    val current = ArrayList<AiLyricsSegment>()
    var currentChars = 0

    forEach { segment ->
        val nextSize = currentChars + segment.text.length
        if (current.isNotEmpty() && (current.size >= MaxTranslatorItemsPerBatch || nextSize > MaxTranslatorCharsPerBatch)) {
            chunks.add(current.toList())
            current.clear()
            currentChars = 0
        }
        current.add(segment)
        currentChars += segment.text.length
    }

    if (current.isNotEmpty()) chunks.add(current.toList())
    return chunks
}

internal fun uniqueTranslationSeparator(segments: List<AiLyricsSegment>): String {
    var separator = "<<<SEP-${UUID.randomUUID()}>>>"
    while (segments.any { segment -> segment.text.contains(separator) }) {
        separator = "<<<SEP-${UUID.randomUUID()}>>>"
    }
    return separator
}

internal const val MaxTranslatorItemsPerBatch = 50
internal const val MaxTranslatorCharsPerBatch = 4000