package moe.rukamori.archivetune.utils

import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult

/**
 * A body that reached the disk cache truncated or corrupt — an interrupted write or a short CDN
 * reply — decodes as an error on every later request: Coil serves the cached bytes again and never
 * fetches a fresh copy. On the first error the entry is dropped and the request runs once more, so a
 * one-off bad write heals itself while the network is up. The retried result comes back as it
 * resolves: a still-failing image reports its real error, and the cache stays empty rather than
 * holding the corrupt bytes.
 */
internal class CorruptImageCacheInterceptor(
    private val diskCache: DiskCache?,
    private val isOnline: () -> Boolean,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val first = chain.proceed(chain.request)
        if (first !is ErrorResult || !isOnline()) return first
        val key = chain.request.diskCacheKey ?: return first
        if (diskCache == null || !runCatching { diskCache.remove(key) }.getOrDefault(false)) return first
        return chain.proceed(chain.request)
    }
}
