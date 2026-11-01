@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.backup

import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.memset
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/**
 * The raw DEFLATE codec over the platform zlib: wBits of -15, which writes and reads the bare stream ZIP entries
 * carry, where the GZIP adapter uses 31 (15 + 16) for its wrapper.
 */
internal actual fun portableDeflate(data: ByteArray): ByteArray {
    if (data.isEmpty()) return data
    return memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
        check(
            deflateInit2_(
                stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY,
                ZLIB_VERSION, sizeOf<z_stream>().toInt(),
            ) == Z_OK,
        )
        try {
            val out = ByteArray(deflateBound(stream.ptr, data.size.toULong()).toInt())
            data.usePinned { input ->
                out.usePinned { output ->
                    stream.next_in = input.addressOf(0).reinterpret()
                    stream.avail_in = data.size.toUInt()
                    stream.next_out = output.addressOf(0).reinterpret()
                    stream.avail_out = out.size.toUInt()
                    check(deflate(stream.ptr, Z_FINISH) == Z_STREAM_END) { "DEFLATE compression failed" }
                    out.copyOf(stream.total_out.toInt())
                }
            }
        } finally { deflateEnd(stream.ptr) }
    }
}

internal actual fun portableInflate(data: ByteArray, size: Int): ByteArray {
    require(size >= 0)
    if (size == 0) return byteArrayOf()
    return memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
        check(inflateInit2_(stream.ptr, -15, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK)
        try {
            val out = ByteArray(size)
            data.usePinned { input ->
                out.usePinned { output ->
                    stream.next_in = if (data.isEmpty()) null else input.addressOf(0).reinterpret()
                    stream.avail_in = data.size.toUInt()
                    stream.next_out = output.addressOf(0).reinterpret()
                    stream.avail_out = out.size.toUInt()
                    // Z_FINISH returns Z_STREAM_END only when the stream ends inside the given output space, so a
                    // stream longer or shorter than the declared entry size fails here.
                    val status = inflate(stream.ptr, Z_FINISH)
                    check(
                        status == Z_STREAM_END && stream.total_out.toInt() == out.size &&
                            stream.total_in.toInt() == data.size,
                    ) { "Invalid DEFLATE stream" }
                    out
                }
            }
        } finally { inflateEnd(stream.ptr) }
    }
}
