package moe.rukamori.archivetune.desktop

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * The text tags a scanned local file carries: title, artist and album, as the baseline's
 * MediaStore/jaudiotagger scan reads them. Every field is null when the file states none —
 * the caller falls back to the file name for the title and never guesses the rest.
 */
internal data class LocalAudioTags(val title: String?, val artist: String?, val album: String?)

/**
 * Reads title/artist/album tags from the file itself: ID3v2 (TIT2/TPE1/TALB, v2.3/v2.4 frames and
 * v2.3 text encodings), FLAC and Ogg Vorbis comments (TITLE/ARTIST/ALBUM), MP4/M4A `ilst`
 * (©nam/©ART/©alb) and RIFF LIST-INFO (INAM/IART/IPRD). Unreadable or unknown layouts return empty
 * tags rather than throw, so one bad file never fails a scan.
 */
internal object LocalTagReader {

    fun read(path: String): LocalAudioTags {
        val file = java.io.File(path)
        if (!file.isFile) return LocalAudioTags(null, null, null)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(64)
                val len = raf.read(header)
                if (len < 12) return LocalAudioTags(null, null, null)
                when {
                    header.matchesAscii(0, "ID3") -> readId3v2(raf, header)
                    header.matchesAscii(0, "fLaC") -> readFlac(raf)
                    header.matchesAscii(0, "OggS") -> readOgg(raf, header, len)
                    header.matchesAscii(0, "RIFF") && header.matchesAscii(8, "WAVE") -> readRiff(raf, file.length())
                    isMp4(header) -> readMp4(raf, file.length())
                    // An MP3 with no ID3v2 can still hold ID3v1 at the tail.
                    isMp3(header) -> readId3v1(raf, file.length())
                    else -> LocalAudioTags(null, null, null)
                }
            }
        } catch (_: Exception) {
            LocalAudioTags(null, null, null)
        }
    }

    private fun isMp4(header: ByteArray): Boolean =
        header.size >= 12 && header.matchesAscii(4, "ftyp") &&
            (header.matchesAscii(8, "M4A") || header.matchesAscii(8, "M4B") || header.matchesAscii(8, "isom") ||
                header.matchesAscii(8, "mp4") || header.matchesAscii(8, "dash"))

    private fun isMp3(header: ByteArray): Boolean =
        header.size >= 2 && header[0].toInt() == 0xFF && (header[1].toInt() and 0xE0) == 0xE0

    // --- ID3v1 / ID3v2 ---

    private fun readId3v1(raf: RandomAccessFile, length: Long): LocalAudioTags {
        if (length < 128) return LocalAudioTags(null, null, null)
        raf.seek(length - 128)
        val tag = ByteArray(128)
        raf.readFully(tag)
        if (!tag.matchesAscii(0, "TAG")) return LocalAudioTags(null, null, null)
        fun text(off: Int) = String(tag, off, 30, Charset.forName("windows-1252")).trim('\u0000', ' ').ifBlank { null }
        return LocalAudioTags(text(3), text(33), text(63))
    }

    private fun readId3v2(raf: RandomAccessFile, header: ByteArray): LocalAudioTags {
        raf.seek(0)
        val head = ByteArray(10)
        raf.readFully(head)
        val major = head[3].toInt() and 0xFF
        val tagSize = synchsafe(head, 6)
        val tagEnd = 10L + tagSize
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        // Extended header: v2.3 is a BE u32 of payload size (+4 flag bytes), v2.4 is synchsafe.
        var pos = 10L
        if ((head[5].toInt() and 0x40) != 0) {
            val ext = ByteArray(4)
            raf.readFully(ext)
            val skip = if (major >= 4) synchsafe(ext, 0) else ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).int
            pos += skip + 4
        }
        val frameHead = ByteArray(10)
        while (pos + 10 <= tagEnd) {
            raf.seek(pos)
            if (raf.read(frameHead) < 10) break
            if (frameHead[0].toInt() == 0) break
            val id = String(frameHead, 0, 4, Charsets.US_ASCII)
            val size = if (major >= 4) synchsafe(frameHead, 4)
                else ByteBuffer.wrap(frameHead, 4, 4).order(ByteOrder.BIG_ENDIAN).int
            if (size <= 0 || pos + 10 + size > tagEnd + tagSize) break
            val payload = ByteArray(size)
            raf.readFully(payload)
            val text = id3Text(payload)
            when (id) {
                "TIT2", "TT2" -> title = text
                "TPE1", "TP1" -> artist = text
                "TALB", "TAL" -> album = text
            }
            if (title != null && artist != null && album != null) break
            pos += 10 + size
        }
        return LocalAudioTags(title, artist, album)
    }

    /** Decodes one ID3v2 text frame: byte 0 names the encoding (0=ISO-8859-1, 1=UTF-16+BOM, 2=UTF-16BE, 3=UTF-8). */
    private fun id3Text(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val charset = when (payload[0].toInt() and 0xFF) {
            1, 2 -> Charsets.UTF_16
            3 -> Charsets.UTF_8
            else -> Charset.forName("ISO-8859-1")
        }
        return String(payload, 1, payload.size - 1, charset)
            .trim('\u0000').split('\u0000').firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun synchsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or (bytes[offset + 3].toInt() and 0x7F)

    // --- Vorbis comments (FLAC metadata block + Ogg packet) ---

    private fun readFlac(raf: RandomAccessFile): LocalAudioTags {
        raf.seek(4)
        while (true) {
            val blockHead = ByteArray(4)
            if (raf.read(blockHead) < 4) break
            val type = blockHead[0].toInt() and 0x7F
            val size = (blockHead[1].toInt() and 0xFF shl 16) or (blockHead[2].toInt() and 0xFF shl 8) or
                (blockHead[3].toInt() and 0xFF)
            if (type == 4) { // VORBIS_COMMENT
                val payload = ByteArray(size)
                raf.readFully(payload)
                return vorbisTags(payload)
            }
            if ((blockHead[0].toInt() and 0x80) != 0) break // last metadata block
            raf.skipBytes(size)
        }
        return LocalAudioTags(null, null, null)
    }

    private fun readOgg(raf: RandomAccessFile, header: ByteArray, len: Int): LocalAudioTags {
        // Walk pages to the first packet carrying the Vorbis comment (type 3) or OpusTags header.
        raf.seek(0)
        val packet = ByteArray(64 * 1024)
        var filled = 0
        var guard = 0
        while (filled < packet.size && guard++ < 4096) {
            val pageHead = ByteArray(27)
            if (raf.read(pageHead) < 27) break
            if (!pageHead.matchesAscii(0, "OggS")) break
            val segments = pageHead[26].toInt() and 0xFF
            val segs = ByteArray(segments)
            raf.readFully(segs)
            var body = 0
            for (seg in segs) body += seg.toInt() and 0xFF
            val page = ByteArray(body)
            raf.readFully(page)
            var at = 0
            for (seg in segs) {
                val segLen = seg.toInt() and 0xFF
                if (filled + segLen > packet.size) return LocalAudioTags(null, null, null)
                System.arraycopy(page, at, packet, filled, segLen)
                filled += segLen
                at += segLen
                if (segLen < 255) {
                    val head = String(packet, 0, minOf(8, filled), Charsets.US_ASCII)
                    if (head.startsWith("\u0003vorbis")) return vorbisTags(packet, filled, 7)
                    if (head.startsWith("OpusTags")) return vorbisTags(packet, filled, 8)
                    filled = 0
                }
            }
        }
        return LocalAudioTags(null, null, null)
    }

    /** Parses a Vorbis comment payload: vendor string, then count + `NAME=value` UTF-8 fields. */
    private fun vorbisTags(payload: ByteArray, length: Int = payload.size, offset: Int = 0): LocalAudioTags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var pos = offset
        fun u32(): Int {
            val v = ByteBuffer.wrap(payload, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
            pos += 4
            return v
        }
        try {
            pos += u32() // vendor string
            val count = u32()
            repeat(count) {
                val n = u32()
                if (pos + n > length) return LocalAudioTags(title, artist, album)
                val line = String(payload, pos, n, Charsets.UTF_8)
                pos += n
                val eq = line.indexOf('=')
                if (eq <= 0) return@repeat
                val value = line.substring(eq + 1).trim().ifBlank { null }
                when (line.substring(0, eq).uppercase()) {
                    "TITLE" -> title = value
                    "ARTIST" -> artist = value
                    "ALBUM" -> album = value
                }
            }
        } catch (_: Exception) {
        }
        return LocalAudioTags(title, artist, album)
    }

    // --- MP4/M4A ilst ---

    private fun readMp4(raf: RandomAccessFile, length: Long): LocalAudioTags {
        val buffer = ByteArray(minOf(length, 8L * 1024 * 1024).toInt())
        raf.seek(0)
        raf.readFully(buffer)
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        val ilst = findBox(buffer, "ilst") ?: return LocalAudioTags(null, null, null)
        walkMetaItems(buffer, ilst.first, ilst.second) { name, value ->
            when (name) {
                "©nam" -> title = value
                "©ART", "aART" -> artist = value
                "©alb" -> album = value
            }
        }
        return LocalAudioTags(title, artist, album)
    }

    /** Locates a named box's [payloadStart, payloadEnd) inside [bytes] at any depth. */
    private fun findBox(bytes: ByteArray, name: String, start: Int = 0, end: Int = bytes.size): Pair<Int, Int>? {
        var pos = start
        while (pos + 8 <= end) {
            var size = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int
            var headLen = 8
            if (size == 1 && pos + 16 <= end) {
                size = ByteBuffer.wrap(bytes, pos + 8, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
                headLen = 16
            }
            if (size < headLen || pos + size > end) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            if (type == name) return (pos + headLen) to (pos + size)
            // Container boxes: descend; meta carries a 4-byte version/flags preamble.
            val childStart = pos + headLen + (if (type == "meta") 4 else 0)
            if (type == "moov" || type == "udta" || type == "meta") {
                findBox(bytes, name, childStart, pos + size)?.let { return it }
            }
            pos += size
        }
        return null
    }

    /** Reads each metadata item's `data` box as UTF-8 text and hands it to [each]. */
    private fun walkMetaItems(
        bytes: ByteArray, start: Int, end: Int, each: (name: String, value: String) -> Unit,
    ) {
        var pos = start
        while (pos + 8 <= end) {
            val size = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int
            if (size < 8 || pos + size > end) break
            val name = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val data = findBox(bytes, "data", pos + 8, pos + size)
            if (data != null && data.second - data.first > 8) {
                // data payload: 4-byte type + 4-byte locale, then the value.
                val text = String(bytes, data.first + 8, data.second - data.first - 8, Charsets.UTF_8).trim()
                if (text.isNotBlank()) each(name, text)
            }
            pos += size
        }
    }

    // --- RIFF LIST-INFO ---

    private fun readRiff(raf: RandomAccessFile, length: Long): LocalAudioTags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        raf.seek(12)
        val head = ByteArray(8)
        var pos = 12L
        while (pos + 8 <= length) {
            raf.seek(pos)
            if (raf.read(head) < 8) break
            val id = String(head, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "LIST" && size > 0 && size < 1 shl 20) {
                val chunk = ByteArray(size)
                raf.readFully(chunk)
                if (!chunk.matchesAscii(0, "INFO")) break
                var at = 4
                while (at + 8 <= chunk.size) {
                    val tagId = String(chunk, at, 4, Charsets.US_ASCII)
                    val n = ByteBuffer.wrap(chunk, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (n <= 0 || at + 8 + n > chunk.size) break
                    val value = String(chunk, at + 8, n, Charsets.UTF_8).trim('\u0000', ' ').ifBlank { null }
                    when (tagId) {
                        "INAM" -> title = value
                        "IART" -> artist = value
                        "IPRD" -> album = value
                    }
                    at += 8 + n + (n and 1)
                }
                break
            }
            pos += 8 + size + (size and 1)
        }
        return LocalAudioTags(title, artist, album)
    }

    private fun ByteArray.matchesAscii(offset: Int, text: String): Boolean {
        if (offset + text.length > size) return false
        for (i in text.indices) if (this[offset + i] != text[i].code.toByte()) return false
        return true
    }
}
