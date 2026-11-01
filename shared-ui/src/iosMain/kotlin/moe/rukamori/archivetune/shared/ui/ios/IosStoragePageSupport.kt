@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import moe.rukamori.archivetune.shared.domain.settings.MaxImageCacheSizeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.SettingsCacheKind
import platform.Foundation.NSURLCache

/**
 * The cache kinds backed by real storage on this host. Remote artwork travels through the shared URL cache the
 * Darwin session uses, so IMAGE reports its disk and memory footprint and a clear empties it plus the decoded-image
 * holder. Songs stream without a managed cache directory, canvases and lyrics keep no on-disk store on this host, so
 * those kinds report no bytes and their clear reports itself unavailable rather than claiming a result.
 */
internal fun iosLaneUsedCacheBytes(): Map<SettingsCacheKind, Long> {
    val cache = NSURLCache.sharedURLCache()
    val image = (cache.currentDiskUsage + cache.currentMemoryUsage).toLong()
    return if (image > 0L) mapOf(SettingsCacheKind.IMAGE to image) else emptyMap()
}

internal suspend fun iosLaneClearCache(graph: IosSessionGraph, kind: SettingsCacheKind, unavailable: () -> Unit) {
    when (kind) {
        SettingsCacheKind.IMAGE -> {
            NSURLCache.sharedURLCache().removeAllCachedResponses()
            graph.artworkLoader.clear()
        }
        SettingsCacheKind.SONG, SettingsCacheKind.CANVAS, SettingsCacheKind.LYRICS -> unavailable()
    }
}

/**
 * Applies the saved image cache limit to the shared URL cache, at open and after the page commits a change. Values
 * are MiB: 0 disables the cache, -1 is unlimited, and a positive value bounds its disk side; the memory side keeps
 * its default except when the cache is disabled.
 */
internal fun iosApplyImageCacheLimit(preferences: PreferenceSnapshot?) {
    val limitMb = preferences?.get(MaxImageCacheSizeKey) ?: 512
    val cache = NSURLCache.sharedURLCache()
    when {
        limitMb == 0 -> {
            cache.diskCapacity = 0uL
            cache.memoryCapacity = 0uL
        }
        limitMb > 0 -> cache.diskCapacity = (limitMb.toLong() * 1024L * 1024L).toULong()
    }
}
