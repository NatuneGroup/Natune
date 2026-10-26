/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics

/**
 * Strips transport wrappers (BOM, Markdown code fence, JSON envelope, HTML-escaped TTML) so that
 * the format readers see the lyric document itself.
 */
object LyricsNormalizer {
    fun normalize(lyrics: String): String {
        val raw = lyrics.removePrefix("\uFEFF").trimLyricsSpace()
        val unwrapped = unwrapJsonEnvelope(stripCodeFence(raw))
        val normalized =
            if (isEscapedTtml(unwrapped)) {
                unwrapped
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&apos;", "'")
            } else {
                unwrapped
            }
        return normalized.trimLyricsSpace()
    }

    // Older caches can hold a provider's JSON envelope verbatim; unwrap it so the TTML inside renders.
    private fun unwrapJsonEnvelope(value: String): String {
        val first = value.firstOrNull() ?: return value
        if (first != '{' && first != '[') return value
        // LRC text also starts with '[', so only a document that actually parses as JSON is an envelope.
        if (LyricsPayload.parseJson(value) == null) return value
        val inner = runCatching { LyricsPayload.unwrap(value) }.getOrNull() ?: return value
        if (inner.isBlank()) return value
        return inner.trimLyricsSpace()
    }

    private fun stripCodeFence(lyrics: String): String {
        if (!lyrics.startsWith("```")) return lyrics

        val lines = lyrics.lines()
        if (lines.size <= 1) return lyrics

        val bodyLines =
            lines.drop(1).let { remainingLines ->
                if (remainingLines.lastOrNull()?.trim() == "```") {
                    remainingLines.dropLast(1)
                } else {
                    remainingLines
                }
            }

        return bodyLines.joinToString("\n").trimLyricsSpace()
    }

    private fun isEscapedTtml(lyrics: String): Boolean {
        val trimmed = lyrics.trimStart()
        return trimmed.startsWith("&lt;tt", ignoreCase = true) ||
            trimmed.contains("&lt;tt", ignoreCase = true) ||
            trimmed.contains("http://www.w3.org/ns/ttml", ignoreCase = true) &&
            trimmed.contains("&lt;", ignoreCase = true)
    }
}
