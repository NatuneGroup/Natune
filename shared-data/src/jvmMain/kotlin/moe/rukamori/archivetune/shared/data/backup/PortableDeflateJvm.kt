package moe.rukamori.archivetune.shared.data.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual fun portableDeflate(data: ByteArray): ByteArray {
    if (data.isEmpty()) return EMPTY_RAW_STREAM
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count <= 0) throw IOException("DEFLATE produced no output")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    } finally {
        deflater.end()
    }
}

internal actual fun portableInflate(data: ByteArray, size: Int): ByteArray {
    require(size >= 0)
    if (size == 0) return byteArrayOf()
    val inflater = Inflater(true)
    try {
        inflater.setInput(data)
        val output = ByteArray(size)
        var written = 0
        while (written < size) {
            val count = inflater.inflate(output, written, size - written)
            if (count > 0) {
                written += count
                continue
            }
            if (inflater.finished()) break
            throw DataFormatException("DEFLATE stream ended early")
        }
        check(written == size && inflater.finished() && inflater.remaining == 0) {
            "DEFLATE stream length mismatch"
        }
        return output
    } finally {
        inflater.end()
    }
}

/** The shortest valid raw DEFLATE stream: one final fixed-Huffman block holding only its end-of-block code. */
private val EMPTY_RAW_STREAM = byteArrayOf(0x03, 0x00)

private const val COPY_BUFFER_BYTES = 64 * 1024
