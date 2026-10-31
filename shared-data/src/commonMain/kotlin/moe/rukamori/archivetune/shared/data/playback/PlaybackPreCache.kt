package moe.rukamori.archivetune.shared.data.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders

/**
 * A resource one prefetch may read. The caller states that it is an https direct file of known length. The reader
 * sends only a bare range request, so a resource that needs headers or an account scope is refused here.
 */
class DirectFile(
    val url: String,
    val lengthBytes: Long,
    val headers: PlaybackHeaders = PlaybackHeaders.NONE,
    val accountScope: String? = null,
) {
    /**
     * The gate for a prefetch. A URL alone does not prove it is direct, so the caller still decides directness. This
     * check only refuses what a bare request could not fetch correctly.
     */
    fun isPrefetchable(): Boolean =
        lengthBytes > 0L &&
            url.startsWith("https://", ignoreCase = true) &&
            headers.isEmpty() &&
            accountScope == null
}

/** Reads the first bytes of one direct file. Platform code implements it over its own HTTP range source. */
interface DirectPrefixReader {
    /** Returns up to [length] bytes from the start of [url]. A result shorter than [length] counts as a failed read. */
    suspend fun readPrefix(url: String, length: Int): ByteArray
}

/**
 * Holds the first bytes of direct HTTP(S) files so the next track can start without waiting on the network. Only a
 * caller that knows a file is direct may name it here. Extractor URLs carry tokens that expire, so they are never
 * prefetched.
 *
 * Nothing here runs on a timer. A caller invokes [prefetch] from playback-driven resolution, and a read that fails or
 * comes back short leaves nothing behind. Held bytes stay within [budgetBytes]; the oldest prefix leaves first.
 */
class PlaybackPreCache(
    private val reader: DirectPrefixReader,
    private val allowed: () -> Boolean = { true },
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val prefixBytes: Int = DEFAULT_PREFIX_BYTES,
) {
    private val lock = Mutex()
    private val prefixes = LinkedHashMap<String, ByteArray>()
    private var heldBytes = 0L

    init {
        require(prefixBytes > 0) { "prefixBytes must be positive" }
        require(budgetBytes >= prefixBytes) { "budgetBytes must hold one prefix" }
    }

    /**
     * Reads the first bytes of [file] when [allowed] says so and the file is prefetchable. Returns true when the prefix is
     * held afterwards. [allowed] carries the user's low-data and Wi-Fi-only choices, so a platform that cannot tell the
     * networks apart must answer conservatively.
     */
    suspend fun prefetch(file: DirectFile): Boolean {
        if (!allowed() || !file.isPrefetchable()) return false
        val wanted = minOf(prefixBytes.toLong(), file.lengthBytes).toInt()
        val bytes = try {
            reader.readPrefix(file.url, wanted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            lock.withLock { discard(file.url) }
            return false
        }
        if (bytes.size != wanted) {
            lock.withLock { discard(file.url) }
            return false
        }
        lock.withLock { store(file.url, bytes) }
        return true
    }

    /** The held first bytes of [url], or null. A read may use them only for offsets inside the returned array. */
    suspend fun prefixOf(url: String): ByteArray? = lock.withLock { prefixes[url] }

    private fun store(url: String, bytes: ByteArray) {
        discard(url)
        prefixes[url] = bytes
        heldBytes += bytes.size.toLong()
        while (heldBytes > budgetBytes && prefixes.isNotEmpty()) {
            discard(prefixes.keys.first())
        }
    }

    private fun discard(url: String) {
        val removed = prefixes.remove(url) ?: return
        heldBytes -= removed.size.toLong()
    }

    companion object {
        /** One mebibyte: the head of a file that covers the first seconds of playback. */
        const val DEFAULT_PREFIX_BYTES = 1048576

        /** Eight mebibytes in total, which holds the next few tracks and no more. */
        const val DEFAULT_BUDGET_BYTES = 8388608L
    }
}
