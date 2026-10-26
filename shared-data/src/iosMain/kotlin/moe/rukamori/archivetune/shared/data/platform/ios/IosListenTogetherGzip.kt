@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.*
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherGzip
import platform.posix.memset
import platform.zlib.*

/**
 * The GZIP adapter the shared Listen Together codec needs on iOS, over the platform zlib. The protobuf-only server
 * may flag a payload as compressed, and the codec then reads the raw bytes, which cannot decode; the JSON server
 * compresses nothing. Matches the other hosts: an unreadable stream returns null, which the codec treats as the raw
 * bytes, and inflation is bounded to [MAX_INFLATED_BYTES] so a hostile frame cannot exhaust memory.
 */
class IosListenTogetherGzip : ListenTogetherGzip {

    override fun compress(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        return memScoped {
            val stream = alloc<z_stream>()
            memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
            // wBits of 15+16 writes the GZIP member format, matching GZIPOutputStream on the other hosts.
            check(
                deflateInit2_(
                    stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 31, 8, Z_DEFAULT_STRATEGY,
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
                        check(deflate(stream.ptr, Z_FINISH) == Z_STREAM_END) { "GZIP compression failed" }
                        out.copyOf(stream.total_out.toInt())
                    }
                }
            } finally { deflateEnd(stream.ptr) }
        }
    }

    override fun decompress(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        return try {
            memScoped {
                val stream = alloc<z_stream>()
                memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
                check(inflateInit2_(stream.ptr, 31, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK)
                try {
                    val out = ByteArray(MAX_INFLATED_BYTES)
                    var produced = 0
                    data.usePinned { input ->
                        out.usePinned { output ->
                            stream.next_in = input.addressOf(0).reinterpret()
                            stream.avail_in = data.size.toUInt()
                            var status: Int
                            do {
                                val offset = stream.total_out.toInt()
                                check(offset < out.size) { "GZIP inflation exceeds limit" }
                                stream.next_out = output.addressOf(offset).reinterpret()
                                stream.avail_out = minOf(64_000, out.size - offset).toUInt()
                                val previousIn = stream.total_in
                                status = inflate(stream.ptr, Z_NO_FLUSH)
                                check(status == Z_OK || status == Z_STREAM_END) { "Invalid GZIP frame" }
                                check(status == Z_STREAM_END || stream.total_out.toInt() > offset || stream.total_in > previousIn)
                            } while (status != Z_STREAM_END)
                            produced = stream.total_out.toInt()
                        }
                    }
                    out.copyOf(produced)
                } finally { inflateEnd(stream.ptr) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val MAX_INFLATED_BYTES = 8 * 1024 * 1024
    }
}
