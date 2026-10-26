package moe.rukamori.archivetune.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import org.jetbrains.skia.Graphics

/** Skia's GPU resource cache never needs more than this for one window of artwork and lyrics. */
private const val SKIA_RESOURCE_CACHE_BYTES = 16 * 1024 * 1024

/** Long enough for the previous song's decoder, artwork and lyrics to be released before the heap is trimmed. */
private const val TRIM_SETTLE_MS = 5_000L

/**
 * Returns freed native memory to the system, as Metrolist's desktop app does. A long session otherwise keeps the peak
 * of every song's decoder buffers and image uploads, because glibc and the macOS allocator keep freed pages mapped.
 *
 * The Skia resource cache is capped once, and the native heap is trimmed a few seconds after each song change. Windows
 * gives its heap back on its own, so it has no trim. Metrolist's MALLOC_ARENA_MAX re-exec is not adopted: replacing
 * the running process breaks the single-instance lock and the restart handoff.
 */
internal object DesktopMemoryHygiene {
    @Volatile
    private var trimUnavailable = false

    fun capRendererCaches() {
        try {
            Graphics.resourceCacheTotalLimit = minOf(Graphics.resourceCacheTotalLimit, SKIA_RESOURCE_CACHE_BYTES)
        } catch (failure: LinkageError) {
            desktopDiagnostic("renderer cache limit unavailable: ${failure.javaClass.simpleName}")
        }
    }

    /** Trims after [songChanges] has been quiet for [TRIM_SETTLE_MS]; the first value is the restored song. */
    suspend fun trimAfterSongChanges(songChanges: Flow<String?>) {
        songChanges.distinctUntilChanged().drop(1).collectLatest {
            delay(TRIM_SETTLE_MS)
            trimNativeHeap()
        }
    }

    private fun trimNativeHeap() {
        if (trimUnavailable) return
        try {
            when {
                Platform.isLinux() -> LinuxAllocator.INSTANCE.malloc_trim(NativeLong(0))
                Platform.isMac() -> MacAllocator.INSTANCE.malloc_zone_pressure_relief(null, NativeLong(0))
                else -> trimUnavailable = true
            }
        } catch (failure: LinkageError) {
            // musl has no malloc_trim; one failure is enough to know.
            trimUnavailable = true
            desktopDiagnostic("native heap trim unavailable: ${failure.javaClass.simpleName}")
        }
    }

    @Suppress("FunctionName")
    private interface LinuxAllocator : Library {
        fun malloc_trim(pad: NativeLong): Int

        companion object {
            val INSTANCE: LinuxAllocator by lazy { Native.load(Platform.C_LIBRARY_NAME, LinuxAllocator::class.java) }
        }
    }

    @Suppress("FunctionName")
    private interface MacAllocator : Library {
        fun malloc_zone_pressure_relief(zone: Pointer?, goal: NativeLong): NativeLong

        companion object {
            val INSTANCE: MacAllocator by lazy { Native.load(Platform.C_LIBRARY_NAME, MacAllocator::class.java) }
        }
    }
}
