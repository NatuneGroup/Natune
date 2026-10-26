package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Use inside HttpStatement.execute so the engine never eagerly buffers an unbounded response. */
suspend fun HttpResponse.readBoundedText(maxBytes: Int): String {
    require(maxBytes > 0)
    val channel = bodyAsChannel()
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(8192)
    var size = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = channel.readAvailable(buffer)
        if (count < 0) break
        if (count == 0) { yield(); continue }
        require(count <= maxBytes - size) { "Provider response exceeds size limit" }
        chunks += buffer.copyOf(count)
        size += count
    }
    val bytes = ByteArray(size)
    var offset = 0
    chunks.forEach { it.copyInto(bytes, offset); offset += it.size }
    return bytes.decodeToString()
}
