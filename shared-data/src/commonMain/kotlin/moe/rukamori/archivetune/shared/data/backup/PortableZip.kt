package moe.rukamori.archivetune.shared.data.backup

/**
 * The raw DEFLATE stream a ZIP entry carries: no zlib or gzip wrapper. Every host supplies the one codec the
 * archive format uses, so a backup written on one host inflates identically on another.
 */
internal expect fun portableDeflate(data: ByteArray): ByteArray

/**
 * Inflates raw DEFLATE [data]; exactly [size] uncompressed bytes are expected. A corrupt, short or trailing
 * stream throws, like the ZipInputStream the desktop host reads with.
 */
internal expect fun portableInflate(data: ByteArray, size: Int): ByteArray

/** The ZIP container did not parse as one, or an entry failed its own integrity check. */
internal class PortableZipNotArchive(cause: Throwable? = null) : IllegalArgumentException("not a portable ZIP archive", cause)

/** The archive has more entries than the format permits. */
internal class PortableZipTooManyEntries : IllegalArgumentException("too many archive entries")

/** An entry or the whole archive exceeds the size limit the caller named. */
internal class PortableZipTooLarge : IllegalArgumentException("archive content exceeds the size limit")

/** One checked entry of a portable backup archive. */
internal class PortableZipEntry(val name: String, val data: ByteArray)

/**
 * A minimal ZIP reader and writer for the backup archives the portable hosts exchange. The writer emits
 * deflated entries with their CRC and sizes in the local header, one central directory and no ZIP64, which is
 * exactly the shape java.util.zip accepts. The reader resolves every entry through the central directory, so it
 * also accepts the data-descriptor form java's ZipOutputStream writes.
 *
 * The compressed and uncompressed sizes are u32 fields, so an archive stays below the 4 GiB ZIP64 threshold;
 * the backup's own 600 MiB bound keeps every archive far under it.
 */
internal object PortableZip {
    private const val LOCAL_HEADER = 0x04034b50L
    private const val CENTRAL_HEADER = 0x02014b50L
    private const val END_RECORD = 0x06054b50L
    private const val METHOD_STORE = 0
    private const val METHOD_DEFLATE = 8
    private const val FLAG_ENCRYPTED = 0x0001
    private const val FLAG_UTF8 = 0x0800
    private const val VERSION = 20
    private const val END_TAIL_BYTES = 22 + 65_536
    private const val DOS_EPOCH_YEAR = 1980

    /**
     * Writes [entries] in order, each name UTF-8 with the EFS flag the Java writer sets for non-ASCII names,
     * each body raw-DEFLATE compressed, timestamped [nowMillis].
     */
    fun write(entries: List<Pair<String, ByteArray>>, nowMillis: Long): ByteArray {
        require(entries.size <= 0xFFFF) { "Too many archive entries" }
        val dosDateTime = dosDateTime(nowMillis)
        val records = entries.map { (name, data) ->
            val nameBytes = name.encodeToByteArray()
            require(nameBytes.size <= 0xFFFF) { "Archive entry name too long" }
            val flags = if (nameBytes.any { it.toInt() and 0xFF >= 0x80 }) FLAG_UTF8 else 0
            Record(nameBytes, flags, crc32(data), portableDeflate(data), data.size)
        }
        var localOffset = 0L
        val locals = records.map { record ->
            val start = localOffset
            localOffset += 30L + record.name.size + record.compressed.size
            start
        }
        require(localOffset + records.sumOf { 46L + it.name.size } + 22L < 0xFFFF_FFFFL) { "Archive too large" }
        val centralSize = records.sumOf { 46L + it.name.size }
        val archive = ByteArray((localOffset + centralSize + 22L).toInt())
        var offset = 0
        records.forEachIndexed { index, record ->
            archive.put32(offset, LOCAL_HEADER)
            archive.put16(offset + 4, VERSION)
            archive.put16(offset + 6, record.flags)
            archive.put16(offset + 8, METHOD_DEFLATE)
            archive.put16(offset + 10, (dosDateTime and 0xFFFF).toInt())
            archive.put16(offset + 12, (dosDateTime ushr 16).toInt())
            archive.put32(offset + 14, record.crc)
            archive.put32(offset + 18, record.compressed.size.toLong())
            archive.put32(offset + 22, record.size.toLong())
            archive.put16(offset + 26, record.name.size)
            archive.put16(offset + 28, 0)
            record.name.copyInto(archive, offset + 30)
            record.compressed.copyInto(archive, offset + 30 + record.name.size)
            offset += 30 + record.name.size + record.compressed.size
            check(offset.toLong() == locals[index] + 30L + record.name.size + record.compressed.size)
        }
        records.forEachIndexed { index, record ->
            archive.put32(offset, CENTRAL_HEADER)
            archive.put16(offset + 4, VERSION)
            archive.put16(offset + 6, VERSION)
            archive.put16(offset + 8, record.flags)
            archive.put16(offset + 10, METHOD_DEFLATE)
            archive.put16(offset + 12, (dosDateTime and 0xFFFF).toInt())
            archive.put16(offset + 14, (dosDateTime ushr 16).toInt())
            archive.put32(offset + 16, record.crc)
            archive.put32(offset + 20, record.compressed.size.toLong())
            archive.put32(offset + 24, record.size.toLong())
            archive.put16(offset + 28, record.name.size)
            archive.put16(offset + 30, 0)
            archive.put16(offset + 32, 0)
            archive.put16(offset + 34, 0)
            archive.put16(offset + 36, 0)
            archive.put32(offset + 38, 0)
            archive.put32(offset + 42, locals[index])
            record.name.copyInto(archive, offset + 46)
            offset += 46 + record.name.size
        }
        archive.put32(offset, END_RECORD)
        archive.put16(offset + 4, 0)
        archive.put16(offset + 6, 0)
        archive.put16(offset + 8, records.size)
        archive.put16(offset + 10, records.size)
        archive.put32(offset + 12, centralSize.toLong())
        archive.put32(offset + 16, localOffset)
        archive.put16(offset + 20, 0)
        return archive
    }

    /**
     * Reads the whole archive, checking every entry before it inflates. [sizeLimit] names each entry's
     * uncompressed byte bound and may itself throw; the total uncompressed bound is [totalLimit].
     */
    fun read(
        archive: ByteArray,
        maxEntries: Int,
        sizeLimit: (name: String) -> Long,
        totalLimit: Long,
    ): List<PortableZipEntry> {
        val eocd = endRecord(archive)
        val count = archive.u16(eocd + 10)
        if (count > maxEntries) throw PortableZipTooManyEntries()
        val centralOffset = archive.u32(eocd + 16)
        require(centralOffset + archive.u32(eocd + 12) <= eocd.toLong() && centralOffset < archive.size)
        val entries = ArrayList<PortableZipEntry>(count)
        var total = 0L
        var position = centralOffset.toInt()
        repeat(count) {
            require(position + 46 <= archive.size)
            if (archive.u32(position) != CENTRAL_HEADER) throw PortableZipNotArchive()
            val flags = archive.u16(position + 8)
            if (flags and FLAG_ENCRYPTED != 0) throw PortableZipNotArchive()
            val method = archive.u16(position + 10)
            if (method != METHOD_STORE && method != METHOD_DEFLATE) throw PortableZipNotArchive()
            val compressedSize = archive.u32(position + 20)
            val size = archive.u32(position + 24)
            val nameLength = archive.u16(position + 28)
            val extraLength = archive.u16(position + 30)
            val commentLength = archive.u16(position + 32)
            val localOffset = archive.u32(position + 42)
            require(position + 46L + nameLength <= archive.size)
            val name = archive.decodeName(position + 46, nameLength, flags and FLAG_UTF8 != 0)
            if (size > sizeLimit(name)) throw PortableZipTooLarge()
            total += size
            if (total > totalLimit) throw PortableZipTooLarge()
            val data = readEntryData(archive, localOffset, method, compressedSize, size, archive.u32(position + 16))
            entries += PortableZipEntry(name, data)
            position += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    /** The uncompressed body of one central-directory entry, read through its local header. */
    private fun readEntryData(
        archive: ByteArray,
        localOffset: Long,
        method: Int,
        compressedSize: Long,
        size: Long,
        crc: Long,
    ): ByteArray {
        require(localOffset + 30 <= archive.size && localOffset < archive.size)
        if (archive.u32(localOffset.toInt()) != LOCAL_HEADER) throw PortableZipNotArchive()
        val dataStart = localOffset + 30 + archive.u16(localOffset.toInt() + 26) + archive.u16(localOffset.toInt() + 28)
        require(dataStart + compressedSize <= archive.size)
        val compressed = archive.copyOfRange(dataStart.toInt(), (dataStart + compressedSize).toInt())
        val data = when (method) {
            METHOD_STORE -> {
                if (compressedSize != size) throw PortableZipNotArchive()
                compressed
            }
            METHOD_DEFLATE -> try {
                portableInflate(compressed, size.toInt())
            } catch (_: Throwable) {
                throw PortableZipNotArchive()
            }
            else -> throw PortableZipNotArchive()
        }
        if (data.size.toLong() != size || crc32(data) != crc) throw PortableZipNotArchive()
        return data
    }

    /** The end-of-central-directory record offset: the last signature whose comment reaches the file end. */
    private fun endRecord(archive: ByteArray): Int {
        val earliest = maxOf(0, archive.size - END_TAIL_BYTES)
        for (offset in archive.size - 22 downTo earliest) {
            if (archive.u32(offset) == END_RECORD && offset + 22 + archive.u16(offset + 20) == archive.size) {
                return offset
            }
        }
        throw PortableZipNotArchive()
    }

    private class Record(
        val name: ByteArray,
        val flags: Int,
        val crc: Long,
        val compressed: ByteArray,
        val size: Int,
    )

    private fun ByteArray.u16(offset: Int): Int {
        require(offset + 2 <= size)
        return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.u32(offset: Int): Long {
        require(offset + 4 <= size)
        return (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun ByteArray.put16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.put32(offset: Int, value: Long) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun ByteArray.decodeName(offset: Int, length: Int, utf8: Boolean): String =
        try {
            copyOfRange(offset, offset + length).decodeToString(throwOnInvalidSequence = utf8)
        } catch (_: Throwable) {
            throw PortableZipNotArchive()
        }

    private val CRC_TABLE = IntArray(256) { index ->
        var value = index
        repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor 0xEDB88320.toInt() else value ushr 1 }
        value
    }

    private fun crc32(bytes: ByteArray): Long {
        var crc = -1
        for (byte in bytes) crc = CRC_TABLE[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
        return (crc xor -1).toLong() and 0xFFFF_FFFFL
    }

    /** DOS packed date-time in the high (date) and low (time) halves, clamped to the 1980 DOS epoch. */
    private fun dosDateTime(millis: Long): Int {
        val days = millis.floorDiv(DAY_MILLIS)
        val (year, month, day) = civilFromDays(days)
        val seconds = ((millis - days * DAY_MILLIS) / 1000L).coerceIn(0L, DAY_MILLIS / 1000L - 1L)
        val dosTime = ((seconds / 3600).toInt() shl 11) or (((seconds % 3600) / 60).toInt() shl 5) or
            ((seconds % 60) / 2).toInt()
        if (year < DOS_EPOCH_YEAR) return (1 shl 21) or (1 shl 16) or 1
        val dosDate = (((year - DOS_EPOCH_YEAR) shl 9) or (month shl 5) or day)
        return (dosDate shl 16) or dosTime
    }

    /** Civil date of a day count since 1970-01-01. */
    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = z.floorDiv(146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (mp + if (mp < 10) 3 else -9).toInt()
        return Triple((y + if (month <= 2) 1 else 0).toInt(), month, day)
    }

    private const val DAY_MILLIS = 86_400_000L
}
