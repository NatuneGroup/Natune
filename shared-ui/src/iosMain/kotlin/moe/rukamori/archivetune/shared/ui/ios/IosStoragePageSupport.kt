@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import moe.rukamori.archivetune.shared.data.platform.ios.IosCanvasCacheStore
import moe.rukamori.archivetune.shared.domain.settings.MaxCanvasCacheSizeKey
import moe.rukamori.archivetune.shared.domain.settings.MaxImageCacheSizeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.SettingsCacheKind
import platform.Foundation.NSURLCache

/**
 * The cache kinds backed by real storage on this host. Remote artwork travels through the shared URL cache the
 * Darwin session uses, so IMAGE reports its disk and memory footprint and a clear empties it plus the decoded-image
 * holder. Canvas loops keep the on-disk store the graph owns, so CANVAS reports its bytes and a clear removes every
 * stored loop. Songs stream without a managed cache directory and lyrics keep no on-disk store on this host, so
 * those kinds report no bytes and their clear reports itself unavailable rather than claiming a result.
 */
internal suspend fun iosLaneUsedCacheBytes(graph: IosSessionGraph): Map<SettingsCacheKind, Long> {
    val cache = NSURLCache.sharedURLCache
    val image = cache.currentDiskUsage.toLong() + cache.currentMemoryUsage.toLong()
    val canvas = graph.canvasStore.usedBytes() ?: 0L
    return buildMap {
        if (image > 0L) put(SettingsCacheKind.IMAGE, image)
        if (canvas > 0L) put(SettingsCacheKind.CANVAS, canvas)
        put(SettingsCacheKind.SONG, graph.songCache.usedBytes())
        put(SettingsCacheKind.LYRICS, graph.lyricsStorage.usedBytes())
    }
}

internal suspend fun iosLaneClearCache(graph: IosSessionGraph, kind: SettingsCacheKind, unavailable: () -> Unit) {
    when (kind) {
        SettingsCacheKind.IMAGE -> {
            NSURLCache.sharedURLCache.removeAllCachedResponses()
            graph.artworkLoader.clear()
        }
        SettingsCacheKind.CANVAS -> graph.canvasStore.clear()
        SettingsCacheKind.SONG -> graph.songCache.clear()
        SettingsCacheKind.LYRICS -> graph.lyricsStorage.clearProviderCache()
    }
}

/**
 * Applies the saved image and canvas cache limits, at open and after the page commits a change. Image values are
 * MiB: 0 disables the shared URL cache, -1 is unlimited, and a positive value bounds its disk side; the memory side
 * keeps its default except when the cache is disabled. A positive canvas limit trims the store oldest-first; a 0
 * canvas limit is the page's own clear path and a negative one means unlimited, so neither trims here.
 */
internal fun iosApplyCacheLimits(preferences: PreferenceSnapshot?, canvasStore: IosCanvasCacheStore) {
    val limitMb = preferences?.get(MaxImageCacheSizeKey) ?: 512
    val cache = NSURLCache.sharedURLCache
    when {
        limitMb == 0 -> {
            cache.diskCapacity = 0uL
            cache.memoryCapacity = 0uL
        }
        limitMb > 0 -> cache.diskCapacity = (limitMb.toLong() * 1024L * 1024L).toULong()
    }
    val canvasLimitMb = preferences?.get(MaxCanvasCacheSizeKey) ?: 256
    if (canvasLimitMb > 0) canvasStore.applyLimitMb(canvasLimitMb)
}
