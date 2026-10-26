package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.rukamori.archivetune.shared.domain.lyrics.ai.Sha256

/**
 * Data-only reader for the pinned IPADIC JAR. Never extracts paths or loads downloaded classes.
 * The archive digest is checked BEFORE interpreting any ZIP sizes/offsets or invoking the inflater.
 * Inflation is platform zlib on iOS; the same parser/factory executes against real JARs on JVM.
 */
internal suspend fun openJapanesePack(
    archive: ByteArray?,
    inflateRaw: suspend (compressed: ByteArray, expectedSize: Int) -> ByteArray,
): JapaneseTokenizer? {
    val context = currentCoroutineContext()
    context.ensureActive()
    if (archive == null || archive.size != KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES) return null
    val digest = Sha256.hexDigest(archive)
    context.ensureActive()
    if (digest != KuromojiJapaneseTokenizerFactory.ARCHIVE_SHA256) return null
    fun u16(at: Int): Int {
        require(at >= 0 && at <= archive.size - 2)
        return (archive[at].toInt() and 255) or ((archive[at + 1].toInt() and 255) shl 8)
    }
    fun int(at: Int): Int = u16(at) or (u16(at + 2) shl 16)
    fun slice(at: Int, size: Int): ByteArray {
        require(at >= 0 && size >= 0 && at <= archive.size - size)
        return archive.copyOfRange(at, at + size)
    }
    // This exact pinned JAR has neither a ZIP comment, split disks, ZIP64 nor encrypted entries.
    val eocd = archive.size - 22
    require(int(eocd) == 0x06054b50 && u16(eocd + 20) == 0)
    require(u16(eocd + 4) == 0 && u16(eocd + 6) == 0)
    val count = u16(eocd + 10)
    require(count == u16(eocd + 8) && count <= 512)
    var cursor = int(eocd + 16)
    require(cursor >= 0 && int(eocd + 12) == eocd - cursor)
    val entries = HashMap<String, Triple<Int, Int, Int>>()
    val pins = KuromojiJapaneseTokenizerFactory.resources.associateBy { it.name }
    repeat(count) {
        context.ensureActive()
        require(int(cursor) == 0x02014b50)
        val nameSize = u16(cursor + 28)
        val name = slice(cursor + 46, nameSize).decodeToString(throwOnInvalidSequence = true)
        val shortName = name.removePrefix(KuromojiJapaneseTokenizerFactory.RESOURCE_PREFIX)
        val pin = pins[shortName]
        if (name.startsWith(KuromojiJapaneseTokenizerFactory.RESOURCE_PREFIX) && pin != null) {
            require(u16(cursor + 8) == 0 && u16(cursor + 10) == 8)
            val compressedSize = int(cursor + 20)
            require(compressedSize in 1..archive.size && int(cursor + 24) == pin.sizeBytes)
            val local = int(cursor + 42)
            require(int(local) == 0x04034b50 && u16(local + 6) == 0 && u16(local + 8) == 8)
            require(int(local + 18) == compressedSize && int(local + 22) == pin.sizeBytes)
            require(slice(local + 30, u16(local + 26)).decodeToString() == name)
            val offset = local + 30 + u16(local + 26) + u16(local + 28)
            require(offset >= 0 && offset <= archive.size - compressedSize)
            require(entries.put(shortName, Triple(offset, compressedSize, pin.sizeBytes)) == null)
        }
        cursor += 46 + nameSize + u16(cursor + 30) + u16(cursor + 32)
    }
    require(cursor == eocd && entries.size == pins.size)
    return KuromojiJapaneseTokenizerFactory.create({ name, maxBytes ->
        context.ensureActive()
        val entry = requireNotNull(entries[name])
        require(entry.third == maxBytes)
        inflateRaw(slice(entry.first, entry.second), maxBytes).also {
            context.ensureActive()
            require(it.size == maxBytes)
        }
    })
}
