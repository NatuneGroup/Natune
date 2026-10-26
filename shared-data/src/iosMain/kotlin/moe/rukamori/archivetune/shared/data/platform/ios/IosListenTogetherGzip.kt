@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherGzip
import platform.posix.memset
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
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
 * GZIP for the iOS Listen Together codec, on the zlib the platform already links. The wire format is the
 * same gzip the Android client writes with `GZIPOutputStream`: deflate wraps at window bits 15 + 16 so the
 * stream carries the gzip header, and inflate opens at 32 + 15 so it accepts gzip or zlib framing.
 */
class IosListenTogetherGzip : ListenTogetherGzip {
    override fun compress(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        memScoped {
            val stream = alloc<z_stream>()
            memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
            check(
                deflateInit2_(
                    stream.ptr,
                    Z_DEFAULT_COMPRESSION,
                    Z_DEFLATED,
                    GZIP_WINDOW_BITS,
                    8,
                    Z_DEFAULT_STRATEGY,
                    ZLIB_VERSION,
                    sizeOf<z_stream>().toInt(),
                ) == Z_OK,
            ) { "GZIP init failed" }
            try {
                val bound = deflateBound(stream.ptr, data.size.convert()).toInt()
                val out = ByteArray(bound)
                data.usePinned { input ->
                    out.usePinned { output ->
                        stream.next_in = input.addressOf(0).reinterpret()
                        stream.avail_in = data.size.toUInt()
                        stream.next_out = output.addressOf(0).reinterpret()
                        stream.avail_out = bound.toUInt()
                        check(deflate(stream.ptr, Z_FINISH) == Z_STREAM_END) { "GZIP compression failed" }
                        return out.copyOf(stream.total_out.toInt())
                    }
                }
            } finally { deflateEnd(stream.ptr) }
        }
    }

    override fun decompress(data: ByteArray): ByteArray? {
        if (data.size < 2) return null
        return runCatching {
            memScoped {
                val stream = alloc<z_stream>()
                memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
                check(inflateInit2_(stream.ptr, AUTO_WINDOW_BITS, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK)
                try {
                    val parts = ArrayList<ByteArray>(4)
                    data.usePinned { input ->
                        stream.next_in = input.addressOf(0).reinterpret()
                        stream.avail_in = data.size.toUInt()
                        val chunk = ByteArray(64_000)
                        var status: Int
                        do {
                            var produced: Int
                            chunk.usePinned { output ->
                                stream.next_out = output.addressOf(0).reinterpret()
                                stream.avail_out = chunk.size.toUInt()
                                status = inflate(stream.ptr, Z_NO_FLUSH)
                                check(status == Z_OK || status == Z_STREAM_END) { "Invalid GZIP stream" }
                                produced = chunk.size - stream.avail_out.toInt()
                            }
                            if (produced > 0) parts.add(chunk.copyOf(produced))
                        } while (status != Z_STREAM_END && stream.avail_in > 0u)
                        check(status == Z_STREAM_END) { "Truncated GZIP stream" }
                    }
                    val merged = ByteArray(parts.sumOf { it.size })
                    var offset = 0
                    parts.forEach { it.copyInto(merged, offset); offset += it.size }
                    merged
                } finally { inflateEnd(stream.ptr) }
            }
        }.getOrNull()
    }

    private companion object {
        const val GZIP_WINDOW_BITS = 15 + 16
        const val AUTO_WINDOW_BITS = 15 + 32
    }
}
