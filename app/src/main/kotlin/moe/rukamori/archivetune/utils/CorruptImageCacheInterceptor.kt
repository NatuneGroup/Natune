package moe.rukamori.archivetune.utils

import coil3.Uri
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageResult

/**
 * A body that reached the disk cache truncated or corrupt — an interrupted write or a short CDN
 * reply — decodes as an error on every later request: Coil serves the cached bytes again and never
 * fetches a fresh copy. On the first error the cached entry is dropped and the request runs exactly
 * once more, so a one-off bad write heals itself while the network is up. A still-failing image
 * reports its real error from the retry.
 */
internal class CorruptImageCacheInterceptor(
    private val diskCache: DiskCache?,
    private val memoryCache: MemoryCache?,
    private val isOnline: () -> Boolean,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val first = chain.proceed()
        if (first !is ErrorResult || !isOnline()) return first
        val request = chain.request
        val key = effectiveImageCacheKey(request.diskCacheKey, request.data) ?: return first
        val disk = diskCache
        val droppedDisk = disk != null && runCatching { disk.remove(key) }.getOrDefault(false)
        val memoryKey = request.memoryCacheKey ?: key
        val memory = memoryCache
        val droppedMemory =
            memory != null &&
                runCatching {
                    memory.keys
                        .filter { it.key == memoryKey }
                        .map { memory.remove(it) }
                        .any { it }
                }.getOrDefault(false)
        // Nothing cached under the key means the error came straight from the network; a retry
        // would only repeat it.
        if (!droppedDisk && !droppedMemory) return first
        return chain.proceed()
    }
}

/**
 * Coil's network fetcher stores a body under the request's explicit disk-cache key, or under the
 * URL when none is set, which is the case for most thumbnails.
 */
internal fun effectiveImageCacheKey(
    diskCacheKey: String?,
    data: Any?,
): String? {
    if (diskCacheKey != null) return diskCacheKey
    val url =
        when (data) {
            is CharSequence, is Uri, is android.net.Uri -> data.toString()
            else -> return null
        }
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return url.takeIf { scheme == "http" || scheme == "https" }
}
