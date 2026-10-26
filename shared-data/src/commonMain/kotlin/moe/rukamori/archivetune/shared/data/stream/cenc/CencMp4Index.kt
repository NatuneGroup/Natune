/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.stream.cenc

import kotlinx.coroutines.delay
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource

/**
 * A structural verdict on a CENC MP4: malformed metadata, or a protection layout this decryptor does not play. It is
 * not an I/O failure. The public entry point turns it into a null answer so the caller can fall through to another
 * source instead of playing garbage.
 */
internal class CencFormatException(message: String) : Exception(message)

/**
 * One contiguous protected byte run. [start] is the absolute file position of its first byte. [streamOffset] is that
 * byte's position inside the owning sample's AES-CTR keystream. The keystream advances only over protected bytes, so a
 * subsampled sample keeps one continuous stream across its protected spans.
 */
internal class CencRun(
    val start: Long,
    val length: Int,
    val iv: ByteArray,
    val streamOffset: Long,
)

/**
 * Rewrites the four type bytes of a box whose type field starts at absolute [position]. A rename never changes a box
 * size or an offset, so no index downstream of the moov has to be recomputed.
 */
internal class CencPatch(
    val position: Long,
    val type: ByteArray,
)

/** Sorted, non-overlapping protected runs and type patches. This is everything the plaintext view needs. */
internal class CencPlaintextMap(
    val runs: Array<CencRun>,
    val patches: Array<CencPatch>,
)

/** The map together with the codec the encrypted sample entry was restored to, such as "flac" or "opus". */
internal class CencIndexResult(
    val codec: String,
    val map: CencPlaintextMap,
)

/** A parsed box. [start] and [end] are offsets inside the [CencWindow] that holds it. */
internal class CencBox(
    val type: String,
    val start: Int,
    val headerSize: Int,
    val size: Long,
) {
    val end: Int get() = (start + size).toInt()
    val contentStart: Int get() = start + headerSize
}

/** The metadata bytes of one box, starting at its header. [base] is the absolute file position of `bytes[0]`. */
internal class CencWindow(
    val base: Long,
    val bytes: ByteArray,
) {
    fun u8At(position: Int): Int = bytes[position].toInt() and 0xff

    fun u32At(position: Int): Long =
        (u8At(position).toLong() shl 24) or
            (u8At(position + 1).toLong() shl 16) or
            (u8At(position + 2).toLong() shl 8) or
            u8At(position + 3).toLong()

    fun boxAt(
        start: Int,
        limit: Int,
    ): CencBox = boxAtOrNull(start, limit) ?: throw CencFormatException("malformed MP4 box header")

    /** Parses the box at [start] when it fits before [limit]; null when the header or size is malformed. */
    fun boxAtOrNull(
        start: Int,
        limit: Int,
    ): CencBox? {
        if (start + 8 > limit) return null
        var size = u32At(start)
        var headerSize = 8
        val type = asciiOf(bytes, start + 4, 4)
        if (size == 1L) {
            if (start + 16 > limit) return null
            size = u64At(start + 8)
            headerSize = 16
        } else if (size == 0L) {
            size = (limit - start).toLong()
        }
        if (size < headerSize || start.toLong() + size > limit) return null
        return CencBox(type, start, headerSize, size)
    }

    private fun u64At(position: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (u8At(position + i).toLong())
        return value
    }

    /** Sibling boxes from [start] up to [limit]; parsing stops quietly at the first malformed header, as the baseline does. */
    fun childBoxes(
        start: Int,
        limit: Int,
    ): List<CencBox> {
        val boxes = ArrayList<CencBox>()
        var position = start
        while (position + 8 <= limit) {
            val box = boxAtOrNull(position, limit) ?: break
            boxes.add(box)
            position = box.end
        }
        return boxes
    }

    /** Children of a container box. Leaf boxes and unknown types have none. */
    fun childrenOf(box: CencBox): List<CencBox> {
        val childStart =
            when (box.type) {
                in PLAIN_CONTAINERS -> box.contentStart
                "stsd" -> box.contentStart + 8
                "enca", "encv" -> sampleEntryChildStart(this, box)
                "sinf", "schi" -> box.contentStart
                else -> return emptyList()
            }
        return childBoxes(childStart, box.end)
    }

    /** A bounded field reader over the content of [box], starting at [from] (default: the content start). */
    fun cursor(
        box: CencBox,
        from: Int = box.contentStart,
    ): CencCursor = CencCursor(bytes, from, box.end)

    fun typePatch(
        box: CencBox,
        type: ByteArray,
    ): CencPatch = CencPatch(base + box.start + 4, type)
}

/** Reads big-endian fields from one box and refuses to read past the end of it. */
internal class CencCursor(
    private val bytes: ByteArray,
    var position: Int,
    private val end: Int,
) {
    val remaining: Int get() = end - position

    fun skip(count: Int) {
        take(count)
    }

    fun u8(): Int {
        val at = take(1)
        return bytes[at].toInt() and 0xff
    }

    fun u16(): Int {
        val at = take(2)
        return ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
    }

    fun u24(): Int {
        val at = take(3)
        return ((bytes[at].toInt() and 0xff) shl 16) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            (bytes[at + 2].toInt() and 0xff)
    }

    fun u32(): Long {
        val at = take(4)
        return ((bytes[at].toLong() and 0xffL) shl 24) or
            ((bytes[at + 1].toLong() and 0xffL) shl 16) or
            ((bytes[at + 2].toLong() and 0xffL) shl 8) or
            (bytes[at + 3].toLong() and 0xffL)
    }

    fun s32(): Long = u32().toInt().toLong()

    fun u64(): Long {
        val at = take(8)
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xffL)
        return value
    }

    fun ascii4(): String {
        val at = take(4)
        return asciiOf(bytes, at, 4)
    }

    fun copy(count: Int): ByteArray {
        val at = take(count)
        return bytes.copyOfRange(at, at + count)
    }

    private fun take(count: Int): Int {
        if (count < 0 || position.toLong() + count > end) throw CencFormatException("MP4 field runs past its box")
        val at = position
        position += count
        return at
    }
}

/** Box types that hold their children directly after the header. */
private val PLAIN_CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf", "edts", "dinf")

private const val MAX_METADATA_BYTES = 16 shl 20
private const val MAX_SAMPLES_PER_BOX = 1 shl 20
private const val MAX_PROTECTED_RUNS = 4 shl 20
private const val IV_BYTES = 16
private const val TOP_HEADER_PROBE_BYTES = 16
private const val IDLE_POLL_MILLIS = 25L
private const val MAX_IDLE_POLLS = 400

private val FREE_TYPE: ByteArray = "free".encodeToByteArray()

internal class TopBox(
    val type: String,
    val start: Long,
    val size: Long,
)

internal class CencTenc(
    val isProtected: Boolean,
    val ivSize: Int,
)

internal class CencTfhd(
    val baseDataOffset: Long?,
    val defaultSampleSize: Long?,
)

internal class CencTrun(
    val dataOffset: Long?,
    val sampleSizes: IntArray,
)

internal class CencSubsample(
    val clearBytes: Int,
    val protectedBytes: Int,
)

internal class CencSenc(
    val ivs: List<ByteArray>,
    val subsamples: List<List<CencSubsample>>,
)

/** Mutable accumulation across fragments, so the final map is sorted and checked once. */
internal class IndexState(
    val totalLength: Long?,
    val perSampleIvSize: Int,
    val trexDefaultSampleSize: Long?,
) {
    val runs = ArrayList<CencRun>()
    val patches = ArrayList<CencPatch>()

    fun addSample(
        samplePosition: Long,
        size: Int,
        iv: ByteArray,
        subsamples: List<CencSubsample>,
    ) {
        if (size <= 0) return
        if (subsamples.isEmpty()) {
            addRun(CencRun(samplePosition, size, iv, 0L))
            return
        }
        var covered = 0L
        for (subsample in subsamples) covered += subsample.clearBytes.toLong() + subsample.protectedBytes
        if (covered > size) throw CencFormatException("subsample map overruns its sample")
        var position = samplePosition
        var streamOffset = 0L
        for (subsample in subsamples) {
            position += subsample.clearBytes
            if (subsample.protectedBytes > 0) {
                addRun(CencRun(position, subsample.protectedBytes, iv, streamOffset))
                streamOffset += subsample.protectedBytes
                position += subsample.protectedBytes
            }
        }
    }

    private fun addRun(run: CencRun) {
        if (runs.size >= MAX_PROTECTED_RUNS) throw CencFormatException("too many protected runs")
        runs.add(run)
    }
}

/**
 * Builds the decryption map of a CENC fragmented MP4 from its boxes. Top-level headers are walked with small reads.
 * Only `moov` and `moof` bodies are read into memory, and `mdat` bodies are skipped by offset. Memory therefore
 * scales with metadata, not with the audio.
 */
internal object CencMp4Index {
    /**
     * Indexes the stream. Throws [CencFormatException] for anything unsupported or malformed. Throws
     * [ByteSourceException] when the upstream stays silent past the bounded wait, and propagates cancellation.
     */
    suspend fun build(source: RandomAccessByteSource): CencIndexResult {
        val total = source.lease.lengthBytes
        val topBoxes = walkTopLevel(source, total)
        val moovTop = topBoxes.firstOrNull { it.type == "moov" } ?: throw CencFormatException("no moov box")
        val moovWindow = readWindow(source, moovTop)
        val moov = moovWindow.boxAt(0, moovWindow.bytes.size)
        val stsdPath = listOf("trak", "mdia", "minf", "stbl", "stsd")

        val tenc = findDescendant(moovWindow, moov, stsdPath, "tenc") ?: throw CencFormatException("no tenc box")
        val schm = findDescendant(moovWindow, moov, stsdPath, "schm")
        if (schm != null) {
            val cursor = moovWindow.cursor(schm)
            cursor.skip(4)
            if (cursor.ascii4() != "cenc") throw CencFormatException("unsupported protection scheme")
        }
        val tencInfo = readTenc(moovWindow, tenc)
        if (!tencInfo.isProtected || (tencInfo.ivSize != 8 && tencInfo.ivSize != 16)) {
            throw CencFormatException("unsupported tenc defaults")
        }

        val sampleTable = descend(moovWindow, moov, listOf("trak", "mdia", "minf", "stbl"))
            ?: throw CencFormatException("no sample table")
        val stsd = moovWindow.childrenOf(sampleTable).firstOrNull { it.type == "stsd" }
            ?: throw CencFormatException("no stsd box")
        val sampleEntry = moovWindow.childBoxes(stsd.contentStart + 8, stsd.end).firstOrNull()
            ?: throw CencFormatException("no sample entry")

        val state = IndexState(total, tencInfo.ivSize, trexDefaultSampleSize(moovWindow, moov))
        val codec = neutralizeSampleEntry(moovWindow, sampleEntry, state.patches)
            ?: throw CencFormatException("encrypted sample entry cannot be restored")
        for (child in moovWindow.childrenOf(moov)) {
            if (child.type == "pssh") state.patches.add(moovWindow.typePatch(child, FREE_TYPE))
        }

        var fragments = 0
        var samples = 0
        for (top in topBoxes) {
            if (top.type != "moof") continue
            val window = readWindow(source, top)
            samples += indexFragment(window, window.boxAt(0, window.bytes.size), state)
            fragments++
        }
        if (fragments == 0 || samples == 0) throw CencFormatException("no encrypted fragments")

        val runs = state.runs
        runs.sortBy { it.start }
        for (i in 1 until runs.size) {
            val previous = runs[i - 1]
            if (runs[i].start < previous.start + previous.length) {
                throw CencFormatException("protected spans overlap")
            }
        }
        state.patches.sortBy { it.position }
        return CencIndexResult(codec, CencPlaintextMap(runs.toTypedArray(), state.patches.toTypedArray()))
    }

    /**
     * Walks the top-level boxes. A known total length must be reached exactly. With an unknown length the walk ends
     * at a clean end of stream. Headers are read with small requests, so a moov or moof is never buffered twice.
     */
    private suspend fun walkTopLevel(
        source: RandomAccessByteSource,
        total: Long?,
    ): List<TopBox> {
        val boxes = ArrayList<TopBox>()
        var position = 0L
        while (total == null || position < total) {
            val head = fetchFully(source, position, TOP_HEADER_PROBE_BYTES)
            if (head.isEmpty()) {
                if (total == null) return boxes
                throw CencFormatException("stream ends before its declared length")
            }
            if (head.size < 8) throw CencFormatException("truncated box header")
            var size = readU32(head, 0)
            val type = asciiOf(head, 4, 4)
            var headerSize = 8
            if (size == 1L) {
                if (head.size < 16) throw CencFormatException("truncated large box header")
                size = readU64(head, 8)
                headerSize = 16
            } else if (size == 0L) {
                val knownTotal = total ?: throw CencFormatException("open-ended box needs a known stream length")
                size = knownTotal - position
            }
            if (size < headerSize) throw CencFormatException("box is smaller than its header")
            if (total != null && position + size > total) throw CencFormatException("box runs past the stream")
            boxes.add(TopBox(type, position, size))
            position += size
        }
        return boxes
    }

    private suspend fun readWindow(
        source: RandomAccessByteSource,
        box: TopBox,
    ): CencWindow {
        if (box.size > MAX_METADATA_BYTES) throw CencFormatException("${box.type} box is too large to index")
        val bytes = fetchFully(source, box.start, box.size.toInt())
        if (bytes.size.toLong() != box.size) throw CencFormatException("truncated ${box.type} box")
        return CencWindow(box.start, bytes)
    }

    /**
     * Reads exactly [length] bytes unless the stream ends first. A zero-byte read means "not yet", so it is polled
     * with a bounded wait; a stream that stays silent past that wait is a failure, never a clean end.
     */
    private suspend fun fetchFully(
        source: RandomAccessByteSource,
        position: Long,
        length: Int,
    ): ByteArray {
        val out = ByteArray(length)
        var got = 0
        var idlePolls = 0
        while (got < length) {
            val read = source.readAt(position + got, out, got, length - got)
            if (read == RandomAccessByteSource.END_OF_STREAM) break
            if (read > 0) {
                got += read
                idlePolls = 0
                continue
            }
            idlePolls++
            if (idlePolls > MAX_IDLE_POLLS) throw ByteSourceException("CENC MP4 source stalled at byte $position")
            delay(IDLE_POLL_MILLIS)
        }
        return if (got == length) out else out.copyOf(got)
    }

    private fun indexFragment(
        window: CencWindow,
        moof: CencBox,
        state: IndexState,
    ): Int {
        for (child in window.childrenOf(moof)) {
            if (child.type == "pssh") state.patches.add(window.typePatch(child, FREE_TYPE))
        }
        var samples = 0
        for (traf in window.childrenOf(moof)) {
            if (traf.type != "traf") continue
            val children = window.childrenOf(traf)
            val tfhdBox = children.firstOrNull { it.type == "tfhd" } ?: throw CencFormatException("traf without tfhd")
            val truns = children.filter { it.type == "trun" }
            if (truns.isEmpty()) continue
            val sencBox = children.firstOrNull { it.type == "senc" } ?: throw CencFormatException("traf without senc")

            val tfhd = parseTfhd(window, tfhdBox)
            val defaultSize = tfhd.defaultSampleSize ?: state.trexDefaultSampleSize
            val senc = parseSenc(window, sencBox, state.perSampleIvSize)
            // Default-base-is-moof: data offsets are measured from the moof start, which is this window's base.
            val anchor = tfhd.baseDataOffset ?: window.base
            var nextRunPosition = anchor
            var sampleIndex = 0
            for (trun in truns) {
                val run = parseTrun(window, trun, defaultSize)
                var samplePosition = run.dataOffset?.let { anchor + it } ?: nextRunPosition
                for (size in run.sampleSizes) {
                    if (sampleIndex >= senc.ivs.size) throw CencFormatException("senc lists fewer samples than trun")
                    if (size < 0 || samplePosition < 0) throw CencFormatException("sample has an invalid position")
                    val totalLength = state.totalLength
                    if (totalLength != null && samplePosition + size > totalLength) {
                        throw CencFormatException("sample lies outside the stream")
                    }
                    state.addSample(samplePosition, size, senc.ivs[sampleIndex], senc.subsamples[sampleIndex])
                    samplePosition += size
                    sampleIndex++
                }
                nextRunPosition = samplePosition
            }
            samples += sampleIndex

            // The per-fragment protection boxes would make a demuxer treat the clear samples as encrypted again.
            for (child in children) {
                when (child.type) {
                    "senc", "saiz", "saio" -> state.patches.add(window.typePatch(child, FREE_TYPE))
                    "sbgp", "sgpd" -> {
                        if (isSeigGroupBox(window, child)) state.patches.add(window.typePatch(child, FREE_TYPE))
                    }
                }
            }
        }
        return samples
    }

    private fun parseTfhd(
        window: CencWindow,
        tfhd: CencBox,
    ): CencTfhd {
        val cursor = window.cursor(tfhd)
        cursor.skip(1)
        val flags = cursor.u24()
        cursor.skip(4) // track_ID
        val baseDataOffset = if (flags and 0x1 != 0) cursor.u64() else null
        if (flags and 0x2 != 0) cursor.skip(4) // sample_description_index
        if (flags and 0x8 != 0) cursor.skip(4) // default_sample_duration
        val defaultSampleSize = if (flags and 0x10 != 0) cursor.u32() else null
        return CencTfhd(baseDataOffset, defaultSampleSize)
    }

    private fun parseTrun(
        window: CencWindow,
        trun: CencBox,
        defaultSampleSize: Long?,
    ): CencTrun {
        val cursor = window.cursor(trun)
        cursor.skip(1)
        val flags = cursor.u24()
        val sampleCount = cursor.u32()
        if (sampleCount > MAX_SAMPLES_PER_BOX) throw CencFormatException("trun sample count is out of range")
        val dataOffset = if (flags and 0x1 != 0) cursor.s32() else null
        if (flags and 0x4 != 0) cursor.skip(4) // first_sample_flags
        val hasDuration = flags and 0x100 != 0
        val hasSize = flags and 0x200 != 0
        val hasFlags = flags and 0x400 != 0
        val hasCompositionOffset = flags and 0x800 != 0
        val fixedSize: Long =
            if (hasSize) {
                -1L
            } else {
                defaultSampleSize ?: throw CencFormatException("trun without sample sizes or a default size")
            }
        val perSample =
            (if (hasDuration) 4 else 0) + (if (hasSize) 4 else 0) +
                (if (hasFlags) 4 else 0) + (if (hasCompositionOffset) 4 else 0)
        val count = sampleCount.toInt()
        if (count.toLong() * perSample > cursor.remaining) throw CencFormatException("trun table runs past its box")
        val sizes = IntArray(count)
        for (i in 0 until count) {
            if (hasDuration) cursor.skip(4)
            sizes[i] = if (hasSize) cursor.u32().toInt() else fixedSize.toInt()
            if (hasFlags) cursor.skip(4)
            if (hasCompositionOffset) cursor.skip(4)
        }
        return CencTrun(dataOffset, sizes)
    }

    private fun parseSenc(
        window: CencWindow,
        senc: CencBox,
        defaultIvSize: Int,
    ): CencSenc {
        val cursor = window.cursor(senc)
        cursor.skip(1)
        val flags = cursor.u24()
        var ivSize = defaultIvSize
        if (flags and 0x1 != 0) {
            cursor.skip(3) // AlgorithmID
            ivSize = cursor.u8()
            cursor.skip(16) // default KID
        }
        if (ivSize != 8 && ivSize != 16) throw CencFormatException("unsupported per-sample IV size")
        val sampleCount = cursor.u32()
        if (sampleCount > MAX_SAMPLES_PER_BOX) throw CencFormatException("senc sample count is out of range")
        val useSubsamples = flags and 0x2 != 0
        val ivs = ArrayList<ByteArray>()
        val subsamples = ArrayList<List<CencSubsample>>()
        repeat(sampleCount.toInt()) {
            // An 8-byte IV is the high half of the counter block. The low half is the block counter from zero.
            val iv = ByteArray(IV_BYTES)
            cursor.copy(ivSize).copyInto(iv)
            ivs.add(iv)
            if (useSubsamples) {
                val subsampleCount = cursor.u16()
                val list = ArrayList<CencSubsample>()
                repeat(subsampleCount) {
                    val clearBytes = cursor.u16()
                    val protectedBytes = cursor.u32()
                    if (protectedBytes > Int.MAX_VALUE) throw CencFormatException("protected span is too large")
                    list.add(CencSubsample(clearBytes, protectedBytes.toInt()))
                }
                subsamples.add(list)
            } else {
                subsamples.add(emptyList())
            }
        }
        return CencSenc(ivs, subsamples)
    }

    private fun readTenc(
        window: CencWindow,
        tenc: CencBox,
    ): CencTenc {
        val cursor = window.cursor(tenc)
        cursor.skip(4) // FullBox version and flags
        cursor.skip(2) // reserved, and the block counts of pattern encryption
        val isProtected = cursor.u8()
        val ivSize = cursor.u8()
        return CencTenc(isProtected == 1, ivSize)
    }

    private fun trexDefaultSampleSize(
        window: CencWindow,
        moov: CencBox,
    ): Long? {
        val mvex = window.childrenOf(moov).firstOrNull { it.type == "mvex" } ?: return null
        val trex = window.childrenOf(mvex).firstOrNull { it.type == "trex" } ?: return null
        if (trex.end - trex.contentStart < 20) return null
        val cursor = window.cursor(trex)
        cursor.skip(16) // FullBox, track_ID, default_sample_description_index, default_sample_duration
        return cursor.u32()
    }

    /**
     * Rewrites the encrypted sample entry to its original format, which comes from the `frma` box, and neutralises
     * the protection wrapper. Returns the codec label, or null when the original format is not playable here.
     */
    private fun neutralizeSampleEntry(
        window: CencWindow,
        entry: CencBox,
        patches: MutableList<CencPatch>,
    ): String? {
        if (entry.type != "enca" && entry.type != "encv") return entry.type.trim().lowercase()
        val children = window.childrenOf(entry)
        val sinf = children.firstOrNull { it.type == "sinf" } ?: return null
        val frma = window.childrenOf(sinf).firstOrNull { it.type == "frma" } ?: return null
        if (frma.end - frma.contentStart < 4) return null
        val formatBytes = window.bytes.copyOfRange(frma.contentStart, frma.contentStart + 4)
        val format = asciiOf(formatBytes, 0, 4)
        // Media3 builds the FLAC stream header from dfLa. Without it the track cannot decode, so refuse it.
        if (format == "fLaC" && children.none { it.type == "dfLa" }) return null
        patches.add(window.typePatch(sinf, FREE_TYPE))
        patches.add(window.typePatch(entry, formatBytes))
        return when (format.trim().lowercase()) {
            "flac", "fla" -> "flac"
            "opus" -> "opus"
            else -> format.trim().lowercase()
        }
    }

    private fun descend(
        window: CencWindow,
        root: CencBox,
        path: List<String>,
    ): CencBox? {
        var current = root
        for (type in path) {
            current = window.childrenOf(current).firstOrNull { it.type == type } ?: return null
        }
        return current
    }

    /** Walks [path] and then looks for [target]. When the path is missing, it searches the whole subtree. */
    private fun findDescendant(
        window: CencWindow,
        root: CencBox,
        path: List<String>,
        target: String,
    ): CencBox? {
        var current = root
        for (type in path) {
            current = window.childrenOf(current).firstOrNull { it.type == type } ?: return findDeep(window, root, target)
        }
        return window.childrenOf(current).firstOrNull { it.type == target } ?: findDeep(window, current, target)
    }

    private fun findDeep(
        window: CencWindow,
        root: CencBox,
        target: String,
    ): CencBox? {
        for (child in window.childrenOf(root)) {
            if (child.type == target) return child
            findDeep(window, child, target)?.let { return it }
        }
        return null
    }
}

/** Offset of the first child box inside an audio sample entry, which depends on the sound-description version. */
private fun sampleEntryChildStart(
    window: CencWindow,
    entry: CencBox,
): Int {
    if (entry.end - entry.contentStart < 28) return entry.end
    val version = (window.u8At(entry.contentStart + 8) shl 8) or window.u8At(entry.contentStart + 9)
    return entry.contentStart + 28 + (if (version == 1) 16 else if (version == 2) 36 else 0)
}

/** True when a sample-group box declares the `seig` grouping, which would re-mark clear samples as encrypted. */
private fun isSeigGroupBox(
    window: CencWindow,
    box: CencBox,
): Boolean {
    if (box.end - box.contentStart < 8) return false
    val cursor = window.cursor(box)
    cursor.skip(4) // FullBox version and flags
    return cursor.ascii4() == "seig"
}

private fun readU32(
    bytes: ByteArray,
    position: Int,
): Long =
    ((bytes[position].toLong() and 0xffL) shl 24) or
        ((bytes[position + 1].toLong() and 0xffL) shl 16) or
        ((bytes[position + 2].toLong() and 0xffL) shl 8) or
        (bytes[position + 3].toLong() and 0xffL)

private fun readU64(
    bytes: ByteArray,
    position: Int,
): Long {
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (bytes[position + i].toLong() and 0xffL)
    return value
}

private fun asciiOf(
    bytes: ByteArray,
    start: Int,
    count: Int,
): String = CharArray(count) { (bytes[start + it].toInt() and 0xff).toChar() }.concatToString()
