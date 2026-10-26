/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.persistence

import moe.rukamori.archivetune.shared.domain.persistence.*

/** Parses the wire protocol only. Deliberately never uses ObjectInputStream, reflection or class loading. */
class LegacyJavaQueueReader {
    fun read(bytes: ByteArray): LegacyQueueDocument {
        require(bytes.size in 5..MAX_BYTES) { "Legacy stream exceeds bounds" }
        return Parser(bytes.copyOf()).read()
    }

    private class Parser(private val bytes: ByteArray) {
        private val input = LegacyInput(bytes)
        private val descriptors = linkedMapOf<Int, LegacyClassDescriptor>()
        private val nodes = linkedMapOf<Int, LegacyNode>()
        private var nextHandle = 0x7e0000
        private var depth = 0

        fun read(): LegacyQueueDocument {
            require(bytes.size in 5..MAX_BYTES) { "Legacy stream exceeds bounds" }
            require(input.readUnsignedShort() == 0xaced && input.readUnsignedShort() == 5) { "Not a Java object stream" }
            val root = value() as? LegacyValue.Reference ?: error("Legacy root must be an object")
            require(input.available() == 0) { "Trailing legacy stream data" }
            val node = nodes[root.handle] as? LegacyNode.Object ?: error("Legacy root must be a queue")
            require(descriptors.getValue(node.descriptorHandle).name == MODEL + "PersistQueue") { "Unexpected legacy root" }
            val document = LegacyQueueDocument(root, descriptors.toMap(), nodes.toMap(), bytes)
            rejectCycles(root, mutableSetOf(), mutableSetOf())
            // Characterize essential access without normalizing, dropping or applying anything.
            LegacyQueueAccess(document).items()
            return document
        }

        private fun handle(): Int {
            require(nextHandle - 0x7e0000 < 250_000) { "Legacy reference limit" }
            return nextHandle++
        }
        private inline fun <T> nested(block: () -> T): T {
            require(++depth <= 128) { "Legacy nesting limit" }
            return try { block() } finally { depth-- }
        }
        private fun value(tag: Int = input.readUnsignedByte()): LegacyValue = nested {
            when (tag) {
                0x70 -> LegacyValue.Null
                0x71 -> LegacyValue.Reference(input.readInt().also { require(it in nodes) { "Invalid legacy reference" } })
                0x74, 0x7c -> {
                    val size = if (tag == 0x74) input.readUnsignedShort().toLong() else input.readLong()
                    require(size in 0..MAX_BYTES.toLong() && size <= input.available())
                    val text = modifiedUtf(ByteArray(size.toInt()).also(input::readFully))
                    val id = handle(); nodes[id] = LegacyNode.Text(text); LegacyValue.Reference(id)
                }
                0x73 -> objectValue()
                0x75 -> arrayValue()
                0x7e -> {
                    val desc = requireNotNull(descriptor())
                    require(descriptors.getValue(desc).flags == 0x12)
                    val id = handle()
                    val name = text(value())
                    require(name in setOf("INITIALIZING", "UNKNOWN", "PAGEABLE", "EXHAUSTED", "FAILED")) {
                        "Unsupported legacy enum value"
                    }
                    nodes[id] = LegacyNode.Enum(desc, name); LegacyValue.Reference(id)
                }
                else -> throw IllegalArgumentException("Unsupported legacy stream token")
            }
        }

        private fun descriptor(): Int? = nested {
            when (input.readUnsignedByte()) {
                0x70 -> null
                0x71 -> input.readInt().also { require(it in descriptors) }
                0x72 -> {
                    val name = input.readUTF()
                    val uid = input.readLong()
                    validateClass(name, uid)
                    val id = handle()
                    val flags = input.readUnsignedByte()
                    require(flags in setOf(2, 3, 12, 0x12)) { "Unsupported legacy class flags" }
                    val count = input.readUnsignedShort()
                    require(count <= 128)
                    val fields = linkedMapOf<String, String>()
                    repeat(count) {
                        val type = input.readUnsignedByte().toChar()
                        require(type in "BCDFIJSZL[")
                        val field = input.readUTF()
                        require(field !in fields)
                        fields[field] = if (type == 'L' || type == '[') text(value()) else type.toString()
                    }
                    // Class annotations are not emitted by the retained writer. Reject rather than drop them.
                    require(input.readUnsignedByte() == 0x78) { "Unsupported annotated legacy descriptor" }
                    val parent = descriptor()
                    descriptors[id] = LegacyClassDescriptor(name, uid, flags, fields, parent)
                    id
                }
                else -> throw IllegalArgumentException("Unsupported legacy class descriptor")
            }
        }

        private fun objectValue(): LegacyValue {
            val desc = requireNotNull(descriptor())
            val id = handle()
            // Placeholder permits references while reading; cycles are rejected before returning.
            nodes[id] = LegacyNode.Object(desc, emptyMap(), emptyMap())
            val fields = linkedMapOf<String, LegacyValue>()
            val annotations = linkedMapOf<String, List<LegacyValue>>()
            val chain = generateSequence(desc) { descriptors.getValue(it).parentHandle }.toList().asReversed()
            for (index in chain) {
                val current = descriptors.getValue(index)
                if (current.flags and 4 != 0) {
                    require(chain.size == 1 && current.flags and 8 != 0)
                    annotations[current.name] = annotations()
                } else {
                    current.fields.forEach { (name, type) ->
                        fields["${current.name}#$name"] = if (type.length == 1 && type[0] in "BCDFIJSZ")
                            primitive(type[0]) else value()
                    }
                    if (current.flags and 1 != 0) annotations[current.name] = annotations()
                }
            }
            nodes[id] = LegacyNode.Object(desc, fields, annotations)
            return LegacyValue.Reference(id)
        }

        private fun annotations(): List<LegacyValue> = buildList {
            while (true) {
                val tag = input.readUnsignedByte()
                if (tag == 0x78) break
                require(size < 100_001)
                if (tag == 0x77 || tag == 0x7a) {
                    val count = if (tag == 0x77) input.readUnsignedByte() else input.readInt()
                    require(count in 0..MAX_BYTES && count <= input.available())
                    add(LegacyValue.Block(ByteArray(count).also(input::readFully).toList()))
                } else add(value(tag))
            }
        }
        private fun arrayValue(): LegacyValue {
            val desc = requireNotNull(descriptor())
            val type = descriptors.getValue(desc).name
            require(type.startsWith('['))
            val id = handle()
            nodes[id] = LegacyNode.Array(desc, emptyList())
            val count = input.readInt()
            require(count in 0..100_000)
            val contents = List(count) { if (type[1] in "BCDFIJSZ") primitive(type[1]) else value() }
            nodes[id] = LegacyNode.Array(desc, contents)
            return LegacyValue.Reference(id)
        }
        private fun primitive(type: Char): LegacyValue.Primitive = LegacyValue.Primitive(type, when (type) {
            'B' -> input.readByte().toLong()
            'C' -> input.readUnsignedShort().toLong()
            'D', 'J' -> input.readLong()
            'F', 'I' -> input.readInt().toLong()
            'S' -> input.readShort().toLong()
            'Z' -> input.readUnsignedByte().toLong().also { require(it in 0..1) }
            else -> error("Unsupported primitive")
        })
        private fun text(value: LegacyValue): String = (nodes.getValue((value as LegacyValue.Reference).handle) as LegacyNode.Text).value

        private fun rejectCycles(ref: LegacyValue.Reference, active: MutableSet<Int>, done: MutableSet<Int>) {
            if (ref.handle in done) return
            require(active.size <= 128 && active.add(ref.handle)) { "Cyclic/deep legacy object graph" }
            val children = when (val node = nodes.getValue(ref.handle)) {
                is LegacyNode.Object -> node.fields.values + node.annotations.values.flatten()
                is LegacyNode.Array -> node.values
                else -> emptyList()
            }
            children.filterIsInstance<LegacyValue.Reference>().forEach { rejectCycles(it, active, done) }
            active.remove(ref.handle); done.add(ref.handle)
        }
    }

    private class LegacyInput(private val bytes: ByteArray) {
        private var position = 0
        fun available(): Int = bytes.size - position
        fun readUnsignedByte(): Int { require(available() > 0) { "Truncated legacy stream" }; return bytes[position++].toInt() and 255 }
        fun readByte(): Byte = readUnsignedByte().toByte()
        fun readUnsignedShort(): Int = (readUnsignedByte() shl 8) or readUnsignedByte()
        fun readShort(): Short = readUnsignedShort().toShort()
        fun readInt(): Int = (readUnsignedShort() shl 16) or readUnsignedShort()
        fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xffffffffL)
        fun readFully(target: ByteArray) {
            require(target.size <= available()) { "Truncated legacy stream" }
            bytes.copyInto(target, 0, position, position + target.size); position += target.size
        }
        fun readUTF(): String = modifiedUtf(ByteArray(readUnsignedShort()).also(::readFully))
    }

    private companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MODEL = "moe.rukamori.archivetune.models."
        const val QUEUE = "moe.rukamori.archivetune.playback.queues."
        val modelNames = setOf("PersistQueue", "MediaMetadata", "MediaMetadata\$Artist", "MediaMetadata\$Album",
            "QueueType", "QueueType\$LIST", "QueueType\$YOUTUBE", "QueueType\$YOUTUBE_PLAYLIST",
            "QueueType\$YOUTUBE_ALBUM_RADIO", "QueueType\$LOCAL_ALBUM_RADIO", "QueueData",
            "QueueData\$YouTubeData", "QueueData\$YouTubeAlbumRadioData", "QueueData\$YouTubePlaylistData",
            "QueueData\$LocalAlbumRadioData")
        val utilityNames = setOf("java.util.ArrayList", "java.util.LinkedList", "java.util.Arrays\$ArrayList",
            "java.util.Collections\$SingletonList", "java.util.Collections\$EmptyList",
            "java.util.Collections\$SingletonSet", "java.util.Collections\$EmptySet",
            "java.util.HashSet", "java.util.LinkedHashSet", "java.util.CollSer",
            "kotlin.collections.EmptyList", "kotlin.collections.EmptySet", "kotlin.collections.builders.SerializedCollection",
            "java.lang.Integer", "java.lang.Long", "java.lang.Number", "java.lang.Boolean",
            "java.lang.Enum", "java.time.Ser")
        fun validateClass(name: String, uid: Long) {
            if (name.startsWith('[')) {
                val component = name.trimStart('[')
                require(name.takeWhile { it == '[' }.length <= 4)
                require((component.length == 1 && component[0] in "BCDFIJSZ") ||
                    (component.startsWith('L') && component.endsWith(';') &&
                        (component.substring(1, component.length - 1) in setOf("java.lang.Object", "java.lang.String", "java.lang.Integer") ||
                            component.substring(1, component.length - 1).let {
                                (it.startsWith(MODEL) && it.removePrefix(MODEL) in modelNames) ||
                                    it in setOf(QUEUE + "FinitePlaylistEntry", QUEUE + "FinitePlaylistSnapshot")
                            }))) { "Unsupported legacy array component" }
                return
            }
            when {
                name.startsWith(MODEL) && name.removePrefix(MODEL) in modelNames ->
                    require(uid == if (name.endsWith("\$YouTubeData")) 2L else 1L) { "Unsupported legacy model version" }
                name in setOf(QUEUE + "FinitePlaylistEntry", QUEUE + "FinitePlaylistSnapshot") -> require(uid == 1L)
                name == QUEUE + "FinitePlaylistPhase" -> require(uid == 0L)
                else -> require(name in utilityNames) { "Unsupported legacy class" }
            }
        }
        fun modifiedUtf(bytes: ByteArray): String = buildString {
            var index = 0
            fun next(): Int = (bytes[index++].toInt() and 255)
            fun continuation(): Int {
                require(index < bytes.size)
                return next().also { require(it and 0xc0 == 0x80) } and 0x3f
            }
            while (index < bytes.size) {
                val first = next()
                val code = when {
                    first in 1..127 -> first
                    first and 0xe0 == 0xc0 -> ((first and 0x1f) shl 6) or continuation()
                    first and 0xf0 == 0xe0 -> ((first and 15) shl 12) or (continuation() shl 6) or continuation()
                    else -> throw IllegalArgumentException("Malformed legacy UTF")
                }
                append(code.toChar())
            }
        }
    }
}
