package moe.rukamori.archivetune.shared.domain.mediainfo

/**
 * Identifies an audio container from the first bytes of a file or stream. The container is what the bytes say,
 * never what a file extension or MIME type claims. Callers fall back to the claimed metadata when [detect] returns
 * null, rather than guessing a container.
 */
enum class AudioContainer(
    val extension: String,
    val mimeType: String,
) {
    FLAC("flac", "audio/flac"),
    MP3("mp3", "audio/mpeg"),
    MP4("m4a", "audio/mp4"),
    OGG("ogg", "audio/ogg"),
    WEBM("webm", "audio/webm"),
    WAV("wav", "audio/wav"),
    ;

    companion object {
        /**
         * Bytes to read for a probe. Comfortably covers every magic-byte check in [detect].
         *
         * This is deliberately not large enough to skip a full ID3v2 tag, which is often several KB once cover art is
         * embedded. That costs only the rare ID3-prefixed MP4 case, which falls back to MP3, the correct answer for the
         * far more common ID3-prefixed MP3.
         */
        const val PROBE_BYTES: Int = 64

        /** Detects the container from [header], or returns null when it matches nothing known. */
        fun detect(header: ByteArray): AudioContainer? {
            if (header.size < MIN_PROBE_BYTES) return null
            return when {
                header.matchesAscii(0, "fLaC") -> FLAC
                header.matchesAscii(0, "OggS") -> OGG
                // RIFF....WAVE
                header.matchesAscii(0, "RIFF") && header.matchesAscii(8, "WAVE") -> WAV
                // Matroska/WebM EBML header: 1A 45 DF A3
                header.u8(0) == 0x1A && header.u8(1) == 0x45 && header.u8(2) == 0xDF && header.u8(3) == 0xA3 -> WEBM
                // MP4/M4A: the ftyp box starts at byte 4, after the size field.
                header.matchesAscii(4, "ftyp") -> MP4
                // A leading ID3 tag can precede either MP3 frames or, rarely, an MP4 stream.
                header.matchesAscii(0, "ID3") -> resolveAfterId3(header) ?: MP3
                // A bare MPEG audio frame sync has its top 11 bits set.
                header.u8(0) == 0xFF && (header.u8(1) and 0xE0) == 0xE0 -> MP3
                else -> null
            }
        }

        /**
         * Skips an ID3v2 tag to see what actually follows it. The tag size is a 28-bit synchsafe integer, so its
         * bytes can never contain a false frame-sync pattern.
         */
        private fun resolveAfterId3(header: ByteArray): AudioContainer? {
            if (header.size < ID3_HEADER_BYTES) return null
            val start = ID3_HEADER_BYTES + header.synchsafeInt(6)
            if (start + FTYP_PROBE_BYTES > header.size) return null
            return if (header.matchesAscii(start + 4, "ftyp")) MP4 else null
        }

        /** Maps a MIME type to a sensible extension when the bytes cannot be probed. */
        fun extensionForMime(mimeType: String?): String =
            when {
                mimeType == null -> "m4a"
                mimeType.contains("flac", true) -> "flac"
                mimeType.contains("mpeg", true) || mimeType.contains("mp3", true) -> "mp3"
                mimeType.contains("ogg", true) || mimeType.contains("opus", true) -> "ogg"
                mimeType.contains("webm", true) -> "webm"
                mimeType.contains("wav", true) -> "wav"
                else -> "m4a"
            }

        private const val MIN_PROBE_BYTES = 12
        private const val ID3_HEADER_BYTES = 10
        private const val FTYP_PROBE_BYTES = 8
    }
}
