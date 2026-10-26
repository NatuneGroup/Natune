/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.stream.qmc

/** The audio container a decrypted QQ Music payload turned out to be, identified only by its fixed leading bytes. */
enum class QmcContainer(
    val mimeType: String,
) {
    FLAC("audio/flac"),
    OGG("audio/ogg"),
    MP3("audio/mpeg"),
    M4A("audio/mp4"),
    WAV("audio/wav"),
    ;

    companion object {
        /**
         * Reads the container from the leading bytes. A payload that decrypts to anything else is refused rather than
         * handed to a decoder: a wrong key produces noise, never a plausible file header, so this check is what keeps a
         * key-derivation mistake from reaching the player.
         */
        fun sniff(data: ByteArray): QmcContainer? {
            if (data.size < 8) return null
            fun at(
                offset: Int,
                text: String,
            ): Boolean = text.indices.all { data[offset + it] == text[it].code.toByte() }
            return when {
                at(0, "fLaC") -> FLAC
                at(0, "OggS") -> OGG
                at(0, "RIFF") -> WAV
                at(0, "ID3") -> MP3
                at(4, "ftyp") -> M4A
                (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xE0) == 0xE0 -> MP3
                else -> null
            }
        }
    }
}

/** The codec label for a decrypted payload. Ogg is Vorbis unless its identification header says Opus. */
internal fun qmcCodecFor(
    container: QmcContainer,
    head: ByteArray,
): String =
    when (container) {
        QmcContainer.FLAC -> "flac"
        QmcContainer.OGG -> oggCodec(head)
        QmcContainer.MP3 -> "mp3"
        QmcContainer.M4A -> "aac"
        QmcContainer.WAV -> "pcm"
    }

/** Ogg identification headers sit in the first few hundred bytes, so only that window is searched. */
private const val OGG_IDENTIFICATION_WINDOW = 256

private fun oggCodec(head: ByteArray): String {
    val window = minOf(head.size, OGG_IDENTIFICATION_WINDOW)
    return if (containsAscii(head, "OpusHead", window)) "opus" else "vorbis"
}

/** True when [needle] occurs entirely inside the first [window] bytes of [data]. */
private fun containsAscii(
    data: ByteArray,
    needle: String,
    window: Int,
): Boolean {
    val bytes = needle.encodeToByteArray()
    for (start in 0..window - bytes.size) {
        var matched = true
        for (index in bytes.indices) {
            if (data[start + index] != bytes[index]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}
