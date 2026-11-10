package moe.rukamori.archivetune.shared.data.persistence

import moe.rukamori.archivetune.shared.domain.settings.*

/** PreferencesProto wire reader. Unknown fields/kinds fail explicitly; never drop an account/device key. */
object LegacyDataStorePreferences {
    // Public wire provenance: AndroidX datastore-preferences-proto/src/main/proto/preferences.proto.
    fun read(bytes: ByteArray): PreferenceSnapshot {
        require(bytes.size <= 2_000_000)
        val map = Proto(bytes)
        val result = linkedMapOf<String, Pair<PreferenceKey<*>, Any>>()
        while (!map.done()) {
            require(map.varint() == 10L) { "Unsupported PreferencesProto map field" }
            val entry = Proto(map.blob())
            var name: String? = null
            var value: Proto? = null
            while (!entry.done()) when (entry.varint()) {
                10L -> { require(name == null); name = entry.string() }
                18L -> { require(value == null); value = Proto(entry.blob()) }
                else -> throw IllegalArgumentException("Unsupported PreferencesProto entry")
            }
            val key = requireNotNull(name)
            require(key !in result && result.size < 100_000) { "Duplicate/excessive PreferencesProto keys" }
            result[key] = parseValue(key, requireNotNull(value))
        }
        return PreferenceSnapshot.of(*result.values.toTypedArray())
    }

    /**
     * [snapshot] narrowed to the keys [includeKey] accepts. Legacy payload readers share this filter, so
     * values the selection excludes (device-bound caches, secure-store blobs, unselected categories)
     * never reach a live store.
     */
    fun filtered(snapshot: PreferenceSnapshot, includeKey: (String) -> Boolean): PreferenceSnapshot =
        PreferenceSnapshot.of(
            *snapshot.keys
                .filter { includeKey(it.name) }
                .mapNotNull { key -> snapshot[key]?.let { value -> key to value } }
                .toTypedArray(),
        )

    private fun parseValue(name: String, value: Proto): Pair<PreferenceKey<*>, Any> {
        require(!value.done())
        val entry: Pair<PreferenceKey<*>, Any> = when (value.varint()) {
            8L -> BooleanPreferenceKey(name) to value.varint().also { require(it in 0..1) }.let { it != 0L }
            21L -> FloatPreferenceKey(name) to Float.fromBits(value.fixed(4).toInt())
            24L -> IntPreferenceKey(name) to value.varint().toInt()
            32L -> LongPreferenceKey(name) to value.varint()
            42L -> StringPreferenceKey(name) to value.string()
            50L -> {
                val set = Proto(value.blob())
                val strings = linkedSetOf<String>()
                while (!set.done()) { require(set.varint() == 10L); strings += set.string() }
                StringSetPreferenceKey(name) to strings.toSet()
            }
            57L -> DoublePreferenceKey(name) to Double.fromBits(value.fixed(8))
            // The shared settings contract has no byte-array kind. Stop, don't silently corrupt it.
            66L -> throw IllegalArgumentException("Byte-array preferences require a byte-array capable host settings adapter")
            else -> throw IllegalArgumentException("Unsupported PreferencesProto value kind")
        }
        require(value.done()) { "Multiple/unknown PreferencesProto value fields" }
        return entry
    }
    private class Proto(private val bytes: ByteArray) {
        private var position = 0
        fun done(): Boolean = position == bytes.size
        private fun byte(): Int { require(position < bytes.size) { "Truncated PreferencesProto" }; return bytes[position++].toInt() and 255 }
        fun varint(): Long {
            var value = 0L
            for (index in 0..9) {
                val byte = byte()
                if (index == 9) require(byte <= 1)
                value = value or ((byte and 127).toLong() shl (index * 7))
                if (byte and 128 == 0) return value
            }
            throw IllegalArgumentException("Invalid PreferencesProto varint")
        }
        fun fixed(count: Int): Long = (0 until count).fold(0L) { value, index -> value or (byte().toLong() shl (index * 8)) }
        fun blob(): ByteArray {
            val count = varint().also { require(it in 0..(bytes.size - position).toLong()) }.toInt()
            return bytes.copyOfRange(position, position + count).also { position += count }
        }
        fun string(): String = blob().decodeToString(throwOnInvalidSequence = true)
    }
}
