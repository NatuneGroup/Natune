package moe.rukamori.archivetune.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.desktop.provider.DEFAULT_DESKTOP_STREAM_USER_AGENT
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator

class HttpRangeSourceTest {
    @Test
    fun boundedChunksCoverTheResourceAndReassembleItExactly() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val ranges = CopyOnWriteArrayList<String>()
        val agents = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { exchange ->
            ranges += exchange.requestHeaders.getFirst("Range").orEmpty()
            agents += exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            serveRange(exchange, payload)
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/media"
            val lease = NativeStreamLease.forRemote(PlaybackLocator.Remote(url))!!
            try {
                val sizing = LongArray(2)
                assertEquals(0, lease.open(sizing))
                assertEquals(payload.size.toLong(), sizing[0])
                assertEquals(1L, sizing[1])

                val read = ByteArray(payload.size)
                val chunk = ByteArray(300_000)
                var position = 0
                var retries = 0
                while (position < payload.size) {
                    val count = lease.read(chunk, chunk.size)
                    when {
                        count > 0 -> {
                            System.arraycopy(chunk, 0, read, position, count)
                            position += count
                        }
                        count == -1 -> {
                            retries++
                            assertTrue(retries < 64, "the chunk boundary should need one retry, not a loop")
                        }
                        else -> error("unexpected code $count at $position")
                    }
                }
                assertContentEquals(payload, read)
                assertEquals(0, lease.read(read, 1))

                assertEquals("bytes=0-65535", ranges.first())
                assertTrue(ranges.size >= 2, "the reader must request a bounded chunk, not one open-ended response")
                assertEquals("bytes=65536-199999", ranges[1])
                assertTrue(ranges.all { it.startsWith("bytes=") && it.substringAfter("bytes").contains('-') })
                assertTrue(agents.all { it == DEFAULT_DESKTOP_STREAM_USER_AGENT }, agents.toString())
            } finally {
                lease.close()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aRefusedStatusIsRecordedForTheFailureMessage() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/forbidden") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/forbidden"
            val lease = NativeStreamLease.forRemote(PlaybackLocator.Remote(url))!!
            try {
                assertTrue(lease.open(LongArray(2)) < 0)
                assertEquals("the source answered HTTP 403", lease.failureDetail())
                // The status travels beside the text, so recovery classifies the refusal without parsing a message.
                assertEquals(403, lease.failureStatus())
            } finally {
                lease.close()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun anExpiredLeaseIsRefusedBeforeARequestAndCarriesNoStatus() {
        val lease = NativeStreamLease.forRemote(
            PlaybackLocator.Remote(url = "https://example.invalid/media", expiresAtEpochMs = System.currentTimeMillis() - 1L),
        )!!
        try {
            assertTrue(lease.open(LongArray(2)) < 0)
            assertEquals("the stream lease has expired", lease.failureDetail())
            assertNull(lease.failureStatus())
        } finally {
            lease.close()
        }
    }

    /** Answers one 206 response for the requested range, capped to the payload. */
    private fun serveRange(exchange: HttpExchange, payload: ByteArray) {
        val spec = exchange.requestHeaders.getFirst("Range")
            ?.removePrefix("bytes=")
            ?.split('-', limit = 2)
            ?: throw IllegalArgumentException("no range")
        val start = spec[0].toInt()
        val requestedEnd = spec.getOrNull(1)?.takeIf(String::isNotBlank)?.toInt() ?: (payload.size - 1)
        val end = minOf(requestedEnd, payload.size - 1)
        val length = end - start + 1
        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
        exchange.sendResponseHeaders(206, length.toLong())
        exchange.responseBody.use { it.write(payload, start, length) }
    }
}
