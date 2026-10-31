package moe.rukamori.archivetune.startup

internal data class NativeFrame(
    val pc: Long, val file: String, val function: String, val offset: Long, val buildId: String,
)
internal data class NativeThread(val id: Long, val name: String, val frames: List<NativeFrame>)
internal data class NativeSignal(val number: Long, val name: String, val code: Long, val codeName: String)
internal data class NativeTombstone(
    val fingerprint: String,
    val timestamp: String,
    val pid: Long,
    val tid: Long,
    val signal: NativeSignal?,
    val abortMessage: String,
    val causes: List<String>,
    val threads: List<NativeThread>,
    val incomplete: Boolean,
)

/** API 31 native traces are protobuf, not UTF-8. Only diagnostic fields are decoded, never memory/log dumps.
 * Wire provenance: platform/system/core, android-12.0.0_r1, debuggerd/proto/tombstone.proto.
 */
internal object NativeTombstoneDecoder {
    fun decode(bytes: ByteArray): NativeTombstone {
        var fingerprint = ""
        var timestamp = ""
        var pid = 0L
        var tid = 0L
        var signal: NativeSignal? = null
        var abort = ""
        var incomplete = false
        val markIncomplete = { incomplete = true }
        val causes = mutableListOf<String>()
        val threadEntries = mutableListOf<Pair<Long, ByteArray>>()
        val root = Fields(bytes, markIncomplete)
        root.forEach { field ->
            when (field.number) {
                2 -> fingerprint = field.text()
                4 -> timestamp = field.text()
                5 -> pid = field.value
                6 -> tid = field.value
                10 -> field.bytes?.let { blob ->
                    val fields = Fields(blob, markIncomplete).all()
                    signal = NativeSignal(fields.number(1), fields.text(2), fields.number(3), fields.text(4))
                }
                14 -> abort = field.text()
                15 -> if (causes.size < 8) field.bytes?.let { causes += Fields(it, markIncomplete).all().text(1) }
                16 -> field.bytes?.let { blob ->
                    val entry = Fields(blob, markIncomplete).all()
                    val id = entry.number(1)
                    entry.firstOrNull { it.number == 2 }?.bytes?.let { thread ->
                        threadEntries += id to thread
                    }
                }
            }
        }
        // Protobuf fields may arrive in any order: the crashing TID can follow the thread map.
        val selected = threadEntries.filter { it.first == tid }.take(1) +
            threadEntries.filter { it.first != tid }.take(4)
        val threads = selected.map { (id, thread) -> decodeThread(id, thread, markIncomplete) }
        return NativeTombstone(fingerprint, timestamp, pid, tid, signal, abort, causes,
            threads, incomplete)
    }

    private fun decodeThread(id: Long, bytes: ByteArray, markIncomplete: () -> Unit): NativeThread {
        var name = ""
        val frames = mutableListOf<NativeFrame>()
        Fields(bytes, markIncomplete).forEach { field ->
            when (field.number) {
                2 -> name = field.text()
                4 -> if (frames.size < 64) field.bytes?.let { frame ->
                    val fields = Fields(frame, markIncomplete).all()
                    frames += NativeFrame(fields.number(1), fields.text(6).substringAfterLast('/'),
                        fields.text(4), fields.number(5), fields.text(8))
                }
            }
        }
        return NativeThread(id, name, frames)
    }

    private data class Field(val number: Int, val value: Long = 0L, val bytes: ByteArray? = null) {
        fun text(): String = bytes?.toString(Charsets.UTF_8)?.take(4096).orEmpty()
    }
    private fun List<Field>.number(number: Int): Long = firstOrNull { it.number == number }?.value ?: 0L
    private fun List<Field>.text(number: Int): String = firstOrNull { it.number == number }?.text().orEmpty()

    private class Fields(private val bytes: ByteArray, private val markIncomplete: () -> Unit) {
        private var position = 0

        fun all(): List<Field> = buildList { this@Fields.forEach { add(it) } }
        fun forEach(block: (Field) -> Unit) {
            while (position < bytes.size) {
                val field = runCatching { next() }.getOrElse { markIncomplete(); return }
                block(field)
            }
        }

        private fun next(): Field {
            val tag = varint()
            require((tag ushr 3) in 1L..Int.MAX_VALUE.toLong())
            val number = (tag ushr 3).toInt()
            return when ((tag and 7).toInt()) {
                0 -> Field(number, value = varint())
                1 -> { skip(8); Field(number) }
                2 -> {
                    val size = varint()
                    require(size in 0..Int.MAX_VALUE.toLong())
                    val available = minOf(size.toInt(), bytes.size - position)
                    val value = bytes.copyOfRange(position, position + available)
                    position += available
                    if (available < size) markIncomplete()
                    Field(number, bytes = value)
                }
                5 -> { skip(4); Field(number) }
                else -> throw IllegalArgumentException("Unsupported protobuf wire kind")
            }
        }

        private fun skip(count: Int) {
            require(count <= bytes.size - position)
            position += count
        }

        private fun varint(): Long {
            var result = 0L
            for (index in 0..9) {
                require(position < bytes.size)
                val value = bytes[position++].toInt() and 255
                if (index == 9) require(value <= 1)
                result = result or ((value and 127).toLong() shl (index * 7))
                if (value and 128 == 0) return result
            }
            throw IllegalArgumentException("Invalid protobuf varint")
        }
    }
}
