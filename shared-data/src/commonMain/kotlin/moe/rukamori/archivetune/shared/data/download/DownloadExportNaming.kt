package moe.rukamori.archivetune.shared.data.download

/** Naming rules for exported downloads, shared by the hosts that offer an export page. */
object DownloadExportNaming {
    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    /**
     * The file name without its extension: artist - title with no filesystem-hostile character, capped by UTF-8
     * bytes so the name plus its extension stays under the 255-byte component limit every target filesystem
     * enforces. The cap reserves the dot and the longest extension the export emits ("flac", "webm").
     */
    fun fileBaseName(title: String, artists: String): String {
        val raw = if (artists.isBlank()) title else "$artists - $title"
        return raw.replace(forbidden, "_").trim().trimEnd('.').capUtf8Bytes(MAX_NAME_BYTES).ifBlank { "song" }
    }

    /** The leading characters whose UTF-8 encoding fits [limit] bytes; a multi-byte character is never split. */
    private fun String.capUtf8Bytes(limit: Int): String {
        if (encodeToByteArray().size <= limit) return this
        var used = 0
        val end = indexOfFirst { char ->
            used += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isSurrogate() -> 2
                else -> 3
            }
            used > limit
        }
        return if (end < 0) this else substring(0, end)
    }

    /** Download files carry no extension, so the container is read from the first bytes. */
    fun extensionOf(header: ByteArray): String {
        fun at(offset: Int, text: String) =
            header.size >= offset + text.length && text.indices.all { header[offset + it] == text[it].code.toByte() }
        return when {
            at(0, "fLaC") -> "flac"
            at(0, "OggS") -> "ogg"
            at(0, "ID3") -> "mp3"
            at(4, "ftyp") -> "m4a"
            at(0, "RIFF") && at(8, "WAVE") -> "wav"
            header.size >= 4 && header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() -> "webm"
            header.size >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            else -> "bin"
        }
    }

    private const val MAX_NAME_BYTES = 255 - 5
}
