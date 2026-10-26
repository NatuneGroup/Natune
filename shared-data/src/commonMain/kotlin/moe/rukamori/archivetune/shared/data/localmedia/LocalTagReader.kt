package moe.rukamori.archivetune.shared.data.localmedia

import moe.rukamori.archivetune.shared.domain.localmedia.LocalMediaTags

/**
 * The bounded pure-Kotlin tag reader the scan applies to a file's head and tail, the portable form of what
 * MediaStore extracted. It covers the containers the scanner admits: ID3v2.3/2.4 (title/artist/album/year/track/
 * genre/TLEN length, APIC pictures, USLT+SYLT lyrics), FLAC and Ogg Vorbis comments plus embedded pictures
 * (`METADATA_BLOCK_PICTURE` and native PICTURE blocks), MP4 `ilst` metadata (`©nam`, `©ART`, `aART`, `©alb`, `©day`,
 * `trkn`, `gnre`, `covr`), and WAV `LIST INFO` (`INAM`, `IART`, `IPRD`, `IGNR`, `ITRK`). Anything it cannot parse
 * yields null fields, never invented values.
 */
object LocalTagReader {

    fun read(head: ByteArray, tail: ByteArray, sizeBytes: Long): LocalMediaTags? {
        if (head.isEmpty() && tail.isEmpty()) return null
        val id3 = readId3v2(head)
        val byContainer = when {
            head.startsWith("fLaC") -> readFlac(head)
            head.startsWith("OggS") -> readOggTags(head)
            head.size >= 12 && head.startsWith("RIFF") && head.startsWith("WAVE", 8) -> readWavInfo(head)
            isMp4(head) -> readMp4(head + tail.ifEmpty { ByteArray(0) })
            else -> null
        }
        if (id3 == null && byContainer == null) return null
        return merge(id3, byContainer)
    }

    private fun merge(a: LocalMediaTags?, b: LocalMediaTags?): LocalMediaTags? {
        if (a == null) return b; if (b == null) return a
        return LocalMediaTags(
            title = a.title ?: b.title, artists = a.artists.ifEmpty { b.artists },
            album = a.album ?: b.album, albumArtist = a.albumArtist ?: b.albumArtist,
            year = a.year ?: b.year, trackNumber = a.trackNumber ?: b.trackNumber,
            durationMs = a.durationMs ?: b.durationMs, genre = a.genre ?: b.genre,
            artwork = a.artwork ?: b.artwork, artworkMime = a.artworkMime ?: b.artworkMime,
            lyrics = a.lyrics ?: b.lyrics,
        )
    }

    // ---- ID3v2 ---------------------------------------------------------------

    private fun readId3v2(head: ByteArray): LocalMediaTags? {
        if (head.size < 10 || !head.startsWith("ID3")) return null
        val major = head[3].toInt() and 0xFF
        if (major != 3 && major != 4) return null
        val tagSize = syncSafeInt(head, 6) + 10
        val end = minOf(tagSize, head.size)
        var pos = 10
        // Skip an extended header when present.
        if (head[5].toInt() and 0x40 != 0) {
            if (pos + 4 > end) return null
            pos += if (major == 4) syncSafeInt(head, pos) else int32(head, pos) + 4
        }
        var title: String? = null; var album: String? = null; var albumArtist: String? = null
        var genre: String? = null; var year: Int? = null; var track: Int? = null; var tlen: Long? = null
        val artists = mutableListOf<String>()
        var artwork: ByteArray? = null; var artworkMime: String? = null; var lyrics: String? = null
        while (pos + 10 <= end) {
            val frameId = head.sliceArray(pos until pos + 4).ascii()
            if (frameId.isBlank() || frameId[0].code == 0) break
            val size = if (major == 4) syncSafeInt(head, pos + 4) else int32(head, pos + 4)
            if (size <= 0 || pos + 10 + size > end) break
            val body = head.sliceArray(pos + 10 until pos + 10 + size)
            when (frameId) {
                "TIT2" -> title = textOf(body)
                "TPE1" -> textOf(body)?.let { artists += splitArtistNames(it) }
                "TALB" -> album = textOf(body)
                "TPE2" -> albumArtist = textOf(body)
                "TCON" -> genre = textOf(body)
                "TRCK" -> track = textOf(body)?.substringBefore('/')?.toIntOrNull()
                "TYER", "TDRC" -> year = textOf(body)?.take(4)?.toIntOrNull()
                "TLEN" -> tlen = textOf(body)?.toLongOrNull()
                "USLT" -> lyrics = usltOf(body)
                "SYLT" -> lyrics = lyrics ?: usltOf(body)
                "APIC" -> artworkOf(body, major).let { pair ->
                    if (pair != null) { artwork = pair.second; artworkMime = pair.first }
                }
            }
            pos += 10 + size
        }
        return LocalMediaTags(title, artists.distinct(), album, albumArtist, year, track, tlen, genre,
            artwork, artworkMime, lyrics)
    }

    /** ID3 text frames: encoding byte then the string; UTF-16 values decode to a terminator boundary. */
    private fun textOf(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val text = decodeId3Text(body) ?: return null
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private fun decodeId3Text(body: ByteArray, offset: Int = 0): String? {
        if (offset >= body.size) return null
        return when (body[0].toInt() and 0xFF) {
            0 -> body.decodeToString(offset).trimEnd('\u0000')
            3 -> body.decodeToString(offset).trimEnd('\u0000')
            1 -> decodeUtf16(body, offset, littleEndian = false)
            2 -> decodeUtf16(body, offset, littleEndian = false)
            else -> null
        }
    }

    private fun decodeUtf16(body: ByteArray, offset: Int, littleEndian: Boolean): String? {
        var start = offset
        if (body.size >= start + 2 && body[start].toInt() == 0xFF && body[start + 1].toInt() == 0xFE) {
            start += 2; return decodeUtf16Raw(body, start, littleEndian = true)
        }
        if (body.size >= start + 2 && body[start].toInt() == 0xFE && body[start + 1].toInt() == 0xFF) {
            start += 2; return decodeUtf16Raw(body, start, littleEndian = false)
        }
        return decodeUtf16Raw(body, start, littleEndian)
    }

    private fun decodeUtf16Raw(body: ByteArray, offset: Int, littleEndian: Boolean): String? {
        val sb = StringBuilder()
        var i = offset
        while (i + 1 < body.size) {
            val code = if (littleEndian) {
                (body[i].toInt() and 0xFF) or ((body[i + 1].toInt() and 0xFF) shl 8)
            } else {
                ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
            }
            if (code == 0) break
            sb.append(code.toChar()); i += 2
        }
        return sb.toString().ifEmpty { null }
    }

    /** USLT: encoding, 3-byte language, content descriptor, then the lyric body. */
    private fun usltOf(body: ByteArray): String? {
        if (body.size < 5) return null
        val encoding = body[0].toInt() and 0xFF
        var pos = 4
        // Skip the content descriptor: NUL for encodings 0/3, double NUL for UTF-16.
        val wide = encoding == 1 || encoding == 2
        while (pos < body.size) {
            if (!wide && body[pos].toInt() == 0) { pos += 1; break }
            if (wide && pos + 1 < body.size && body[pos].toInt() == 0 && body[pos + 1].toInt() == 0) { pos += 2; break }
            pos++
        }
        if (pos >= body.size) return null
        val bytes = body.copyOfRange(pos, body.size).copyOf().let {
            ByteArray(it.size + 1).also { target -> it.copyInto(target); target[0] = encoding.toByte() }
        }
        return decodeId3Text(bytes)?.trim()
    }

    private fun artworkOf(body: ByteArray, major: Int): Pair<String, ByteArray>? {
        if (body.size < 5) return null
        val encoding = body[0].toInt() and 0xFF
        var pos = 1
        val mime: String
        if (major == 3) {
            val end = body.indexOfByte(0, pos); if (end < 0) return null
            mime = body.decodeToString(pos, end); pos = end + 1
        } else {
            val end = body.indexOfByte(0, pos); if (end < 0) return null
            mime = body.decodeToString(pos, end); pos = end + 1
        }
        pos += 1 // picture type
        val wide = encoding == 1 || encoding == 2
        while (pos < body.size) {
            if (!wide && body[pos].toInt() == 0) { pos += 1; break }
            if (wide && pos + 1 < body.size && body[pos].toInt() == 0 && body[pos + 1].toInt() == 0) { pos += 2; break }
            pos++
        }
        if (pos >= body.size) return null
        val bytes = body.copyOfRange(pos, body.size)
        val sniffed = sniffImageMime(bytes) ?: return null
        return sniffed to bytes
    }

    // ---- FLAC / Ogg Vorbis comments -----------------------------------------

    private fun readFlac(head: ByteArray): LocalMediaTags? {
        if (!head.startsWith("fLaC")) return null
        var pos = 4
        var tags: LocalMediaTags? = null
        while (pos + 4 <= head.size) {
            val type = head[pos].toInt() and 0x7F
            val length = (head[pos + 1].toInt() and 0xFF shl 16) or
                (head[pos + 2].toInt() and 0xFF shl 8) or (head[pos + 3].toInt() and 0xFF)
            pos += 4
            if (pos + length > head.size) break
            val block = head.sliceArray(pos until pos + length)
            when (type) {
                4 -> tags = merge(tags, vorbisComments(block))
                6 -> tags = merge(tags, flacPicture(block))
            }
            pos += length
            if (head[pos - length - 4].toInt() and 0x80 != 0) break
        }
        return tags
    }

    private fun flacPicture(block: ByteArray): LocalMediaTags? {
        if (block.size < 32) return null
        var pos = 4 // picture type
        val mimeLen = int32be(block, pos); pos += 4
        if (pos + mimeLen > block.size) return null
        val mime = block.decodeToString(pos, pos + mimeLen); pos += mimeLen
        val descLen = int32be(block, pos); pos += 4 + descLen + 16 // description + w/h/depth/colors
        val dataLen = int32be(block, pos); pos += 4
        if (pos + dataLen > block.size) return null
        val bytes = block.copyOfRange(pos, pos + dataLen)
        val sniffed = sniffImageMime(bytes) ?: return null
        return LocalMediaTags(null, emptyList(), null, null, null, null, null, null, bytes, sniffed, null)
    }

    private fun readOggTags(head: ByteArray): LocalMediaTags? {
        // The Vorbis comment packet is the second packet of the stream; the head holds it for ordinary files.
        val marker = head.indexOf(byteArrayOf(1, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(),
            'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte()))
        if (marker >= 0) {
            val comments = head.copyOfRange(marker + 7, head.size)
            return vorbisComments(comments)
        }
        val opus = head.indexOf("OpusTags".encodeToByteArray())
        if (opus >= 0) return vorbisComments(head.copyOfRange(opus + 8, head.size))
        return null
    }

    private fun vorbisComments(data: ByteArray): LocalMediaTags? {
        if (data.size < 8) return null
        var pos = 0
        val vendorLen = leInt(data, pos); pos += 4 + vendorLen
        if (pos + 4 > data.size) return null
        val count = leInt(data, pos); pos += 4
        var title: String? = null; var album: String? = null; var albumArtist: String? = null
        var genre: String? = null; var year: Int? = null; var track: Int? = null
        val artists = mutableListOf<String>()
        var artwork: ByteArray? = null; var artworkMime: String? = null; var lyrics: String? = null
        repeat(count.coerceAtMost(200)) {
            if (pos + 4 > data.size) return@repeat
            val len = leInt(data, pos); pos += 4
            if (pos + len > data.size) return@repeat
            val entry = data.decodeToString(pos, pos + len); pos += len
            val eq = entry.indexOf('='); if (eq <= 0) return@repeat
            val name = entry.substring(0, eq).uppercase()
            val value = entry.substring(eq + 1)
            when (name) {
                "TITLE" -> title = value
                "ARTIST" -> artists += splitArtistNames(value)
                "ALBUM" -> album = value
                "ALBUMARTIST", "ALBUM ARTIST" -> albumArtist = value
                "DATE", "YEAR" -> year = value.take(4).toIntOrNull()
                "TRACKNUMBER" -> track = value.substringBefore('/').toIntOrNull()
                "GENRE" -> genre = value
                "LYRICS", "UNSYNCEDLYRICS", "LYRICIST" -> lyrics = lyrics ?: value
                "METADATA_BLOCK_PICTURE" -> {
                    val decoded = runCatching { base64Decode(value) }.getOrNull()
                    if (decoded != null) flacPicture(decoded)?.let { artwork = it.artwork; artworkMime = it.artworkMime }
                }
            }
        }
        return LocalMediaTags(title, artists.distinct(), album, albumArtist, year, track, null, genre,
            artwork, artworkMime, lyrics)
    }

    // ---- MP4 ilst ------------------------------------------------------------

    private fun readMp4(bytes: ByteArray): LocalMediaTags? {
        var pos = skipId3(bytes)
        var title: String? = null; var album: String? = null; var albumArtist: String? = null
        var genre: String? = null; var year: Int? = null; var track: Int? = null
        val artists = mutableListOf<String>()
        var artwork: ByteArray? = null; var artworkMime: String? = null
        fun walk(start: Int, end: Int, path: List<String>) {
            var p = start
            while (p + 8 <= end) {
                val size = int32be(bytes, p).let { if (it == 0) end - p else it }
                if (size < 8 || p + size > end) break
                val box = bytes.sliceArray(p + 4 until p + 8).ascii()
                val inside = path + box
                when {
                    box in MP4_CONTAINERS -> walk(p + 8, p + size, inside)
                    box == "meta" -> walk(p + 12, p + size, inside) // meta carries a full-box version word
                    box == "data" -> {
                        val atom = path.getOrElse(path.lastIndex - 1) { "" }
                        if (size >= 16) {
                            val value = bytes.copyOfRange(p + 16, p + size)
                            when (atom) {
                                "©nam" -> title = value.decodeToString().trim().ifEmpty { null } ?: title
                                "©ART" -> value.decodeToString().trim().takeIf { it.isNotEmpty() }
                                    ?.let { artists += splitArtistNames(it) }
                                "aART" -> albumArtist = value.decodeToString().trim().ifEmpty { null } ?: albumArtist
                                "©alb" -> album = value.decodeToString().trim().ifEmpty { null } ?: album
                                "©day" -> year = value.decodeToString().trim().take(4).toIntOrNull() ?: year
                                "©gen", "gnre" -> genre = value.decodeToString().trim().ifEmpty { null } ?: genre
                                "trkn" -> if (value.size >= 8) {
                                    track = ((value[3].toInt() and 0xFF) shl 8) or (value[4].toInt() and 0xFF)
                                }
                                "covr" -> {
                                    sniffImageMime(value)?.let { artwork = value; artworkMime = it }
                                }
                            }
                        }
                    }
                }
                p += size
            }
        }
        walk(pos, bytes.size, emptyList())
        return LocalMediaTags(title, artists.distinct(), album, albumArtist, year, track, null, genre,
            artwork, artworkMime, null)
    }

    // ---- WAV LIST INFO --------------------------------------------------------

    private fun readWavInfo(head: ByteArray): LocalMediaTags? {
        if (head.size < 12) return null
        var pos = 12
        var title: String? = null; var album: String? = null; var genre: String? = null
        var track: Int? = null
        val artists = mutableListOf<String>()
        while (pos + 8 <= head.size) {
            val id = head.sliceArray(pos until pos + 4).ascii()
            val size = leInt(head, pos + 4)
            if (id == "LIST" && head.sliceArray(pos + 8 until minOf(pos + 12, head.size)).ascii() == "INFO") {
                var q = pos + 12
                val listEnd = minOf(pos + 8 + size, head.size)
                while (q + 8 <= listEnd) {
                    val tag = head.sliceArray(q until q + 4).ascii()
                    val len = leInt(head, q + 4)
                    if (q + 8 + len > head.size) break
                    val value = head.decodeToString(q + 8, q + 8 + len).trimEnd(' ')
                    when (tag) {
                        "INAM" -> title = value
                        "IART" -> artists += splitArtistNames(value)
                        "IPRD" -> album = value
                        "IGNR" -> genre = value
                        "ITRK" -> track = value.toIntOrNull()
                    }
                    q += 8 + len + (len and 1)
                }
            }
            pos += 8 + size + (size and 1)
        }
        return LocalMediaTags(title, artists.distinct(), album, null, null, track, null, genre, null, null, null)
    }

    // ---- helpers --------------------------------------------------------------

    private fun skipId3(head: ByteArray): Int =
        if (head.size >= 10 && head.startsWith("ID3")) 10 + syncSafeInt(head, 6) else 0

    private fun syncSafeInt(b: ByteArray, pos: Int): Int =
        (b[pos].toInt() and 0x7F shl 21) or (b[pos + 1].toInt() and 0x7F shl 14) or
            (b[pos + 2].toInt() and 0x7F shl 7) or (b[pos + 3].toInt() and 0x7F)

    private fun int32(b: ByteArray, pos: Int): Int = int32be(b, pos)

    private fun int32be(b: ByteArray, pos: Int): Int =
        (b[pos].toInt() and 0xFF shl 24) or (b[pos + 1].toInt() and 0xFF shl 16) or
            (b[pos + 2].toInt() and 0xFF shl 8) or (b[pos + 3].toInt() and 0xFF)

    private fun leInt(b: ByteArray, pos: Int): Int =
        (b[pos].toInt() and 0xFF) or (b[pos + 1].toInt() and 0xFF shl 8) or
            (b[pos + 2].toInt() and 0xFF shl 16) or (b[pos + 3].toInt() and 0xFF shl 24)

    /** The retained artwork rule: only jpg/png/webp payloads are kept, by magic, not by declared MIME. */
    private fun sniffImageMime(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0].toInt() == 0xFF && bytes[1].toInt() == 0xD8 && bytes[2].toInt() == 0xFF -> "image/jpeg"
        bytes.size >= 8 && bytes[0].toInt() == 0x89 && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
        else -> null
    }

    private fun splitArtistNames(value: String): List<String> =
        value.split(',', ';', '/', '&').map { it.trim() }.filter { it.isNotEmpty() }

    private fun ByteArray.indexOfByte(value: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun ByteArray.ascii(): String = decodeToString()

    private fun ByteArray.startsWith(value: String, offset: Int = 0): Boolean {
        val bytes = value.encodeToByteArray()
        if (offset + bytes.size > size) return false
        for (i in bytes.indices) if (this[offset + i] != bytes[i]) return false
        return true
    }

    private fun base64Decode(value: String): ByteArray? {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = value.filter { it in table || it == '=' }
        val out = ArrayList<Byte>()
        var buffer = 0; var bits = 0
        for (c in clean) {
            if (c == '=') break
            buffer = (buffer shl 6) or table.indexOf(c); bits += 6
            if (bits >= 8) { bits -= 8; out += ((buffer ushr bits) and 0xFF).toByte() }
        }
        return out.toByteArray()
    }

    private val MP4_CONTAINERS = setOf("moov", "udta", "ilst", "trak", "mdia", "minf", "stbl")
    private val MP4_BRANDS = setOf("ftyp")
}
