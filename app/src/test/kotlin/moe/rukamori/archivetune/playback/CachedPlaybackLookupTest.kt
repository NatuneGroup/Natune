package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.audiosource.PlaybackSourceChain
import moe.rukamori.archivetune.audiosource.PlaybackSourceSnapshot
import moe.rukamori.archivetune.constants.AudioSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedPlaybackLookupTest {
    private val id = "video"
    private val qobuz = PlaybackSourceChain.cacheProbeKey(AudioSourceType.QOBUZ, id)!!
    private val tidal = PlaybackSourceChain.cacheProbeKey(AudioSourceType.TIDAL, id)!!
    private val itag = PlaybackSourceChain.itagCacheKey(id, 251)!!
    private val entries = mutableMapOf<String, Entry>()
    private val lookup = CachedPlaybackLookup(
        recordedLength = { key, includePlayer ->
            entries[key]?.let { listOfNotNull(it.downloadLength, it.playerLength.takeIf { _ -> includePlayer }).maxOrNull() }
        },
        continuousLength = { key, window, includePlayer ->
            entries[key]?.let { continuousCachedLength(it.download + if (includePlayer) it.player else emptyList(), window.position, window.length) } ?: 0L
        },
        completeDownload = { key ->
            entries[key]?.let { row -> row.downloadLength?.let { length -> continuousCachedLength(row.download, 0L, length) >= length } } == true
        },
    )

    @Test
    fun completeCopiesFollowTheEnabledPreferenceOrder() {
        entries[qobuz] = complete(1_000L)
        entries[tidal] = complete(2_000L)
        entries[id] = complete(500L)
        assertEquals(qobuz, find()!!.key)
        assertEquals(tidal, find(order = listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE))!!.key)
        assertEquals(1_000L, find()!!.window.length)
    }

    @Test
    fun disabledOrUnreachableSourcesCannotSupplyOfflineBytes() {
        entries[qobuz] = complete(1_000L)
        assertNull(find(enabled = setOf(AudioSourceType.YOUTUBE)))
        assertNull(find(order = listOf(AudioSourceType.YOUTUBE, AudioSourceType.QOBUZ)))
        entries[id] = complete(500L)
        assertNull(find(enabled = emptySet()))
    }

    @Test
    fun authenticityRejectionExcludesEvenACompletePersistentCopy() {
        entries[qobuz] = complete(1_000L)
        assertNull(find(authenticity = setOf(AudioSourceType.QOBUZ)))
        entries[id] = complete(500L)
        assertEquals(id, find(authenticity = setOf(AudioSourceType.QOBUZ))!!.key)
    }

    @Test
    fun aRejectedTransportRequiresACompleteDownloadAndNeverUsesThePlayerCache() {
        entries[qobuz] = Entry(downloadLength = 1_000L, playerLength = 2_000L,
            download = listOf(span(0L, 1_000L)), player = listOf(span(0L, 2_000L)))
        val saved = find(transport = setOf(AudioSourceType.QOBUZ))!!
        assertEquals(1_000L, saved.window.length)
        assertFalse(saved.includePlayerCache)
        entries[qobuz] = entries.getValue(qobuz).copy(download = listOf(span(0L, 500L)))
        assertNull(find(transport = setOf(AudioSourceType.QOBUZ)))
        assertNull(find(transport = setOf(AudioSourceType.QOBUZ), requestedLength = 100L))
    }

    @Test
    fun adjacentPersistentAndPlayerSpansCanCoverTheWholeFileButAHoleCannot() {
        entries[qobuz] = Entry(downloadLength = 2_000L, playerLength = 2_000L,
            download = listOf(span(0L, 1_000L)), player = listOf(span(1_000L, 1_000L)))
        assertTrue(find()!!.includePlayerCache)
        assertNull(find(includePlayer = false))
        entries[qobuz] = entries.getValue(qobuz).copy(player = listOf(span(1_001L, 999L)))
        assertNull(find())
        entries[qobuz] = entries.getValue(qobuz).copy(player = listOf(CachedSpan(1_000L, 1_000L, false)))
        assertNull(find())
    }

    @Test
    fun cachedSpansWithoutAKnownLengthDoNotProveACompleteOfflineFile() {
        entries[qobuz] = Entry(player = listOf(span(0L, 1_000L)))
        assertNull(find())
        assertEquals(100L, find(requestedLength = 100L)!!.window.length)
    }

    @Test
    fun itagCoverageUsesItsOwnLengthOrItsFormatAndNeverTheBareKeysLength() {
        entries[itag] = Entry(player = listOf(span(0L, 1_000L)))
        entries[id] = Entry(downloadLength = 10L) // This metadata belongs to another rendition.
        assertNull(find(youtubeItag = 251))
        assertEquals(itag, find(youtubeItag = 251, formatLength = 1_000L)!!.key)
        assertNull(find(youtubeItag = 251, formatLength = 1_001L))
        entries[itag] = entries.getValue(itag).copy(playerLength = 1_000L)
        assertEquals(itag, find(youtubeItag = 251, formatLength = 10_000L)!!.key)
    }

    @Test
    fun legacyLengthFallbackUsesTheSameCoverageAndSourceRejectionGates() {
        entries[id] = Entry(download = listOf(span(0L, 500L)), player = listOf(span(500L, 500L)))
        assertEquals(id, find(legacyLength = 1_000L)!!.key)
        assertNull(find(legacyLength = 1_001L))
        assertNull(find(legacyLength = 1_000L, enabled = setOf(AudioSourceType.QOBUZ)))
        assertNull(find(legacyLength = 1_000L, authenticity = setOf(AudioSourceType.YOUTUBE)))
        assertNull(find(legacyLength = 1_000L, transport = setOf(AudioSourceType.YOUTUBE)))
    }

    @Test
    fun aCachedTailCanServeABoundedReadButCannotEnterAnOfflineQueue() {
        entries[id] = Entry(playerLength = 1_000L, player = listOf(span(100L, 900L)))
        assertNull(find())
        val tail = find(position = 100L, requestedLength = 300L)!!
        assertEquals(CachedReadWindow(100L, 300L), tail.window)
    }

    private fun find(
        order: List<AudioSourceType> = listOf(AudioSourceType.QOBUZ, AudioSourceType.TIDAL, AudioSourceType.YOUTUBE),
        enabled: Set<AudioSourceType> = order.toSet(),
        authenticity: Set<AudioSourceType> = emptySet(),
        transport: Set<AudioSourceType> = emptySet(),
        includePlayer: Boolean = true,
        youtubeItag: Int? = null,
        formatLength: Long? = null,
        legacyLength: Long? = null,
        position: Long = 0L,
        requestedLength: Long = -1L,
    ) = lookup.find(id, position, requestedLength, includePlayer, youtubeItag, formatLength,
        PlaybackSourceSnapshot(order, enabled), authenticity, transport,
        sourceOf = { key ->
            if (key == id || key == itag) AudioSourceType.YOUTUBE
            else AudioSourceType.values().firstOrNull { PlaybackSourceChain.cacheProbeKey(it, id) == key }
        },
        spotifyTagOf = { null },
        spotifyCacheAllowed = { false },
        legacyContentLength = legacyLength,
    )

    private data class Entry(
        val downloadLength: Long? = null,
        val playerLength: Long? = null,
        val download: List<CachedSpan> = emptyList(),
        val player: List<CachedSpan> = emptyList(),
    )

    private fun complete(length: Long) = Entry(downloadLength = length, download = listOf(span(0L, length)))
    private fun span(position: Long, length: Long) = CachedSpan(position, length, true)
}
