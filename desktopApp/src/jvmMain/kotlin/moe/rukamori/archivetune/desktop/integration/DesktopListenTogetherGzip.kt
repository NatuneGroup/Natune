package moe.rukamori.archivetune.desktop.integration

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherGzip

/**
 * The GZIP adapter that the shared Listen Together codec needs before it can read compressed envelopes. The codec
 * compresses nothing without an adapter, but the protobuf-only server may flag a payload as compressed, and the codec
 * then reads the raw bytes, which cannot decode. The Android host provides the same inflate path.
 *
 * An unreadable stream returns null, which the codec treats as the raw bytes, so one bad frame cannot end the
 * connection. Inflation is bounded: a payload that expands past [MAX_INFLATED_BYTES] also returns null, so a hostile
 * peer cannot exhaust memory through a small compressed frame. The bound matches the socket's inbound frame cap.
 */
internal class DesktopListenTogetherGzip : ListenTogetherGzip {

    override fun compress(data: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { gzip -> gzip.write(data) }
        return buffer.toByteArray()
    }

    override fun decompress(data: ByteArray): ByteArray? =
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { gzip -> gzip.readBounded() }
        } catch (unreadable: IOException) {
            null
        }

    private fun InputStream.readBounded(): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = read(chunk)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_INFLATED_BYTES) return null
            out.write(chunk, 0, read)
        }
    }

    private companion object {
        const val MAX_INFLATED_BYTES = 8 * 1024 * 1024
        const val READ_CHUNK_BYTES = 8 * 1024
    }
}
