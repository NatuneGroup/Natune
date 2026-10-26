/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serves the playback fixtures over loopback HTTP, with single byte ranges, so that progressive
 * audio seeks the way it does against a real server.
 *
 * The server binds the first free port of 3190 through 3199, the only range the mission allows
 * for local validation sockets.
 */
internal class FixtureHttpServer private constructor(
    private val server: HttpServer,
    private val workers: ExecutorService,
    private val root: File,
) : AutoCloseable {
    private val delays = ConcurrentHashMap<String, Long>()

    /** The server address, such as `http://127.0.0.1:3190`. */
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    /** Holds the next response for [path] back by [millis], so the client sees a stall. */
    fun delayNextResponse(path: String, millis: Long) {
        delays[path] = millis
    }

    override fun close() {
        server.stop(0)
        workers.shutdownNow()
    }

    private fun serve(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            delays.remove(path)?.let { Thread.sleep(it) }
            // The JDK server closes the socket after each exchange, and says so only to clients that
            // ask. Without this header FFmpeg reuses the closed socket to soft-seek and then reads EOF.
            exchange.responseHeaders.set("Connection", "close")
            val file = resolve(path)
            if (file == null) {
                exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
                return
            }
            val bytes = file.readBytes()
            exchange.responseHeaders.set("Content-Type", contentType(file.name))
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            val requestedRange = exchange.requestHeaders.getFirst("Range")
            if (requestedRange == null) {
                exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                return
            }
            val range = parseRange(requestedRange, bytes.size)
            if (range == null) {
                exchange.sendResponseHeaders(HTTP_RANGE_NOT_SATISFIABLE, -1)
                return
            }
            exchange.responseHeaders.set("Content-Range", "bytes ${range.first}-${range.last}/${bytes.size}")
            val slice = bytes.copyOfRange(range.first, range.last + 1)
            exchange.sendResponseHeaders(HTTP_PARTIAL_CONTENT, slice.size.toLong())
            exchange.responseBody.write(slice)
        } finally {
            exchange.close()
        }
    }

    private fun resolve(path: String): File? {
        val base = root.canonicalFile
        val file = base.resolve(path.removePrefix("/")).canonicalFile
        return file.takeIf { it.isFile && it.startsWith(base) }
    }

    /** The inclusive byte span of one `bytes=` range, or null when it cannot be satisfied. */
    private fun parseRange(header: String, size: Int): IntRange? {
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val first = spec.substringBefore('-')
        val last = spec.substringAfter('-')
        val range = if (first.isEmpty()) {
            val suffix = last.toIntOrNull() ?: return null
            (size - suffix.coerceAtMost(size)) until size
        } else {
            val start = first.toIntOrNull() ?: return null
            val end = if (last.isEmpty()) size - 1 else last.toIntOrNull() ?: return null
            start..minOf(end, size - 1)
        }
        return range.takeUnless { it.isEmpty() }
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "ts" -> "video/mp2t"
        "mpd" -> "application/dash+xml"
        "m4s" -> "video/iso.segment"
        else -> "application/octet-stream"
    }

    companion object {
        private const val FIRST_PORT = 3190
        private const val LAST_PORT = 3199
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** Starts a server for [root] on the first free port of the validation range. */
        fun start(root: File): FixtureHttpServer {
            for (port in FIRST_PORT..LAST_PORT) {
                val server = try {
                    HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
                } catch (_: BindException) {
                    continue
                }
                val workers = Executors.newCachedThreadPool()
                server.executor = workers
                val fixtures = FixtureHttpServer(server, workers, root)
                server.createContext("/") { exchange -> fixtures.serve(exchange) }
                server.start()
                return fixtures
            }
            error("no free port in $FIRST_PORT..$LAST_PORT for the fixture server")
        }
    }
}
