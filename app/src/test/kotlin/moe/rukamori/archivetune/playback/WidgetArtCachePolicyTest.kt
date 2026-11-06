package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetArtCachePolicyTest {
    @Test
    fun fileNamesAreStableAndHashCollisionSafe() {
        // Two distinct URIs whose 32-bit String.hashCode() collide; the old name scheme reused
        // one file for both. The SHA-256 name must keep them apart.
        val firstUri = "art://MA7ZAxCYStyjr9EL"
        val secondUri = "art://JVJ3N6L3lAFQuaqE"
        assertEquals(firstUri.hashCode(), secondUri.hashCode())

        val first = WidgetArtCachePolicy.fileNameFor(firstUri)
        val second = WidgetArtCachePolicy.fileNameFor(secondUri)

        assertNotEquals(first, second)
        assertEquals(first, WidgetArtCachePolicy.fileNameFor(firstUri))
        assertTrue(first.matches(Regex("widget_art_[0-9a-f]{64}\\.jpg")))
    }

    @Test
    fun evictionKeepsTheNewestEntriesWithinTheEntryLimit() {
        val newest =
            (0 until WidgetArtCachePolicy.MAX_ENTRIES).map { index ->
                cacheFile("newest-$index", lastModifiedEpochMs = 10_000L - index, sizeBytes = 1_024L)
            }
        val oldest =
            (0 until 3).map { index ->
                cacheFile("oldest-$index", lastModifiedEpochMs = 1_000L - index, sizeBytes = 1_024L)
            }

        val evicted = WidgetArtCachePolicy.filesToEvict(newest + oldest, keepFileName = newest.first().fileName)

        assertEquals(oldest.map { it.fileName }.toSet(), evicted)
    }

    @Test
    fun evictionAlsoHonorsTheByteBudget() {
        val files =
            (0 until 20).map { index ->
                cacheFile(
                    "track-$index",
                    lastModifiedEpochMs = 10_000L - index,
                    sizeBytes = 1L * 1024 * 1024,
                )
            }

        val evicted = WidgetArtCachePolicy.filesToEvict(files, keepFileName = files.first().fileName)

        assertEquals(files.takeLast(4).map { it.fileName }.toSet(), evicted)
    }

    @Test
    fun theArtworkBeingWrittenIsNeverEvicted() {
        val keep = cacheFile("keep", lastModifiedEpochMs = 1L, sizeBytes = 1_024L)
        val newer =
            (0 until WidgetArtCachePolicy.MAX_ENTRIES + 3).map { index ->
                cacheFile("newer-$index", lastModifiedEpochMs = 10_000L - index, sizeBytes = 1_024L)
            }

        val evicted = WidgetArtCachePolicy.filesToEvict(newer + keep, keepFileName = keep.fileName)

        assertFalse(evicted.contains(keep.fileName))
        assertEquals(3, evicted.size)
    }

    @Test
    fun legacyHashNamedFilesAreEvictedOldestFirst() {
        val legacy =
            (0 until 100).map { index ->
                WidgetArtCacheFile(
                    fileName = "widget_art_${index.toString(16)}.jpg",
                    lastModifiedEpochMs = 5_000L - index,
                    sizeBytes = 10L,
                )
            }
        val current = cacheFile("current", lastModifiedEpochMs = 10_000L, sizeBytes = 10L)

        val evicted = WidgetArtCachePolicy.filesToEvict(legacy + current, keepFileName = current.fileName)

        val expected =
            legacy
                .sortedByDescending { it.lastModifiedEpochMs }
                .drop(WidgetArtCachePolicy.MAX_ENTRIES - 1)
                .map { it.fileName }
                .toSet()
        assertEquals(expected, evicted)
    }

    @Test
    fun unrelatedAndEmptyCacheFilesAreNotKeptAsArtwork() {
        val unrelated = WidgetArtCacheFile("unrelated.tmp", lastModifiedEpochMs = 1L, sizeBytes = 10L)
        val empty = WidgetArtCacheFile("widget_art_0.jpg", lastModifiedEpochMs = 2L, sizeBytes = 0L)

        assertTrue(WidgetArtCachePolicy.filesToEvict(listOf(unrelated), keepFileName = null).isEmpty())
        assertEquals(setOf(empty.fileName), WidgetArtCachePolicy.filesToEvict(listOf(empty), keepFileName = null))
    }

    private fun cacheFile(
        id: String,
        lastModifiedEpochMs: Long,
        sizeBytes: Long,
    ) = WidgetArtCacheFile(
        fileName = WidgetArtCachePolicy.fileNameFor(id),
        lastModifiedEpochMs = lastModifiedEpochMs,
        sizeBytes = sizeBytes,
    )
}
