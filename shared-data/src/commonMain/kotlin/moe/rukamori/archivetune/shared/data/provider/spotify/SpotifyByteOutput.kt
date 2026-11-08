package moe.rukamori.archivetune.shared.data.provider.spotify

/** Small bounded protocol payloads; shared replacement for the retained JVM byte output. */
internal class SpotifyByteOutput(capacity: Int = 32) {
    private val bytes = ArrayList<Byte>(capacity)
    fun write(value: Int) { bytes.add(value.toByte()) }
    fun write(value: ByteArray) { for (byte in value) bytes.add(byte) }
    fun size(): Int = bytes.size
    fun toByteArray(): ByteArray = bytes.toByteArray()
}
