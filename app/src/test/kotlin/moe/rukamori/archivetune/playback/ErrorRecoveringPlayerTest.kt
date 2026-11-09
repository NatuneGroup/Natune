package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ErrorRecoveringPlayerTest {
    @Test
    fun everyTransportSeekPreparesAnIdleSourceErrorExactlyOnceAndKeepsPlayIntent() {
        val cases = listOf(
            Navigation(0, 1, 0L) { seekToNext() },
            Navigation(0, 1, 0L) { seekToNextMediaItem() },
            Navigation(1, 0, 0L) { seekToPrevious() },
            Navigation(1, 0, 0L) { seekToPreviousMediaItem() },
            Navigation(0, 2, 4_200L) { seekTo(2, 4_200L) },
            Navigation(1, 1, 4_200L) { seekTo(4_200L) },
            Navigation(1, 1, 0L) { seekToDefaultPosition() },
            Navigation(1, 2, 0L) { seekToDefaultPosition(2) },
        )
        for (playing in listOf(false, true)) for (case in cases) {
            val queue = FakeQueue(listOf(item("A"), item("B"), item("C")), case.from, playWhenReady = playing)
            var recovered = 0
            val wrapper = ErrorRecoveringPlayer(queue.player) { recovered++; queue.calls += "recover" }

            case.seek(wrapper)

            assertEquals(1, recovered)
            assertEquals(1, queue.prepares)
            assertEquals(case.to, queue.index)
            assertEquals(case.positionMs, queue.positionMs)
            assertEquals(playing, queue.playWhenReady)
            assertEquals("recover", queue.calls.first())
            assertEquals("prepare", queue.calls.last())
        }
    }

    @Test
    fun normalPlaybackAndAnIntentionalStopDoNotGainAnImplicitPrepare() {
        for (state in listOf(Player.STATE_READY, Player.STATE_IDLE)) {
            val queue = FakeQueue(listOf(item("A"), item("B")), 0, state = state, error = null)
            val wrapper = ErrorRecoveringPlayer(queue.player) { error("No source error to recover") }
            wrapper.seekToNext()
            assertEquals(1, queue.index)
            assertEquals(0, queue.prepares)
            assertEquals(listOf("seekToNext"), queue.calls)
        }
    }

    @Test
    fun aBoundaryOrAnEmptyQueueDoesNotRetryTheFailedCurrentItem() {
        val last = FakeQueue(listOf(item("A")), 0)
        val empty = FakeQueue(emptyList(), C.INDEX_UNSET)
        for (queue in listOf(last, empty)) {
            ErrorRecoveringPlayer(queue.player) { error("No destination") }.seekToNextMediaItem()
            assertEquals(0, queue.prepares)
            assertTrue(queue.calls.isEmpty())
        }
    }

    @Test
    fun reentrantNavigationFromRecoveryAndSeekCallbacksCannotSkipTwice() {
        val queue = FakeQueue(listOf(item("A"), item("B"), item("C")), 0)
        lateinit var wrapper: ErrorRecoveringPlayer
        wrapper = ErrorRecoveringPlayer(queue.player) { wrapper.seekToNext() }
        queue.onSeek = { wrapper.seekToNext() }

        wrapper.seekToNext()

        assertEquals(1, queue.index)
        assertEquals(1, queue.prepares)
        assertEquals(listOf("seekToNext", "prepare"), queue.calls)
    }

    @Test
    fun aQueueChangedDuringRecoveryNeverReceivesTheOldSelection() {
        val queue = FakeQueue(listOf(item("A"), item("B")), 0)
        val wrapper = ErrorRecoveringPlayer(queue.player) { queue.items.add(item("new")) }

        wrapper.seekToDefaultPosition(1)

        assertEquals(0, queue.index)
        assertEquals(0, queue.prepares)
        assertTrue(queue.calls.isEmpty())
    }

    @Test
    fun aSeekSupersededByAnotherSelectionDoesNotPrepareThatSelection() {
        val queue = FakeQueue(listOf(item("A"), item("B"), item("C")), 0)
        queue.onSeek = { queue.index = 2 }
        ErrorRecoveringPlayer(queue.player) {}.seekToNext()
        assertEquals(2, queue.index)
        assertEquals(0, queue.prepares)
    }

    @Test
    fun offlineNextSkipsUnavailableItemsWithoutRemovingThemAndKeepsPlayIntent() {
        val first = item("cached")
        val last = item("cached")
        val local = item("file:///music/a/b.mp3")
        val original = listOf(item("failed-online"), item("partial"), first, local, last)
        val queue = FakeQueue(original, 0, playWhenReady = false)

        ErrorRecoveringPlayer(queue.player, offlinePolicy()) {}.seekToNextMediaItem()

        assertEquals(original, queue.items)
        assertSame(first, queue.player.currentMediaItem)
        assertEquals(2, queue.index)
        assertTrue(queue.removed.isEmpty())
        assertEquals(1, queue.prepares)
        assertFalse(queue.playWhenReady)
        assertFalse(queue.calls.any { it.startsWith("set") })
    }

    @Test
    fun offlinePreviousSkipsUnavailableItemsAndDefaultPositionKeepsTheExactDuplicate() {
        val first = item("cached")
        val last = item("cached")
        val queue = FakeQueue(listOf(first, item("online"), last), 2)
        ErrorRecoveringPlayer(queue.player, offlinePolicy()) {}.seekToPreviousMediaItem()
        assertSame(first, queue.player.currentMediaItem)
        assertEquals(1, queue.prepares)

        val tapped = FakeQueue(listOf(item("online"), first, item("partial"), last), 0)
        ErrorRecoveringPlayer(tapped.player, offlinePolicy()) {}.seekToDefaultPosition(3)
        assertEquals(3, tapped.index)
        assertEquals(4, tapped.items.size)
        assertSame(last, tapped.player.currentMediaItem)
        assertEquals(1, tapped.prepares)
    }

    @Test
    fun anUnavailableExplicitTapOrRevokedCacheCannotChooseAnUnrelatedSong() {
        val queue = FakeQueue(listOf(item("online"), item("cached")), 0)
        ErrorRecoveringPlayer(queue.player, offlinePolicy()) { error("Tap must be declined") }.seekToDefaultPosition(0)
        assertTrue(queue.calls.isEmpty())
        var available = true
        val revoked = AutoOfflineSongs(isOffline = { true }, isFullyCachedId = { it == "cached" && available })
        ErrorRecoveringPlayer(queue.player, revoked) { available = false }.seekToDefaultPosition(1)
        assertEquals(0, queue.prepares)
        assertTrue(queue.calls.isEmpty())
        assertEquals(2, queue.items.size)
    }

    @Test
    fun offlineNavigationUsesTheLiveShuffleOrderAndLeavesTheQueueIntact() {
        val local = item("file:///music/local.mp3")
        val cached = item("cached")
        val original = listOf(item("online"), cached, item("partial"), local)
        val queue = FakeQueue(original, 0)
        queue.shuffle = true
        queue.order = listOf(0, 3, 1, 2)
        ErrorRecoveringPlayer(queue.player, offlinePolicy()) {}.seekToNext()
        assertEquals(original, queue.items)
        assertSame(local, queue.player.currentMediaItem)
        assertEquals(3, queue.index)
        assertEquals(listOf(0, 3, 1, 2), queue.order)
        assertEquals(1, queue.prepares)
    }

    @Test
    fun repeatAndShuffleScansTerminateWhenNoEligibleItemExists() {
        var checked = 0
        val next = mapOf(0 to 2, 2 to 1, 1 to 0)
        assertEquals(C.INDEX_UNSET, offlineNavigationIndex(0, 3, { next.getValue(it) }) { checked++; false })
        assertEquals(3, checked)
        val queue = FakeQueue(listOf(item("online"), item("partial")), 0)
        queue.repeat = Player.REPEAT_MODE_ALL
        ErrorRecoveringPlayer(queue.player, offlinePolicy()) { error("No eligible destination") }.seekToNext()
        assertEquals(0, queue.prepares)
        assertTrue(queue.calls.isEmpty())
    }

    @Test
    fun replacingAQueueAfterASourceErrorKeepsEveryEntryAndValidatesTheSelectionBeforePreparing() {
        val queue = FakeQueue(listOf(item("old")), 0)
        val cached = item("cached")
        val local = item("content://media/audio/42")
        val replacement = listOf(item("online"), cached, local)
        ErrorRecoveringPlayer(queue.player, offlinePolicy()) {}.setMediaItems(replacement, 1, 8_000L)
        assertEquals(replacement, queue.items)
        assertSame(cached, queue.player.currentMediaItem)
        assertEquals(1, queue.index)
        assertEquals(8_000L, queue.positionMs)
        assertEquals(1, queue.prepares)
    }

    @Test
    fun aReplacementWithoutAnExplicitSelectionStartsAtTheFirstPlayableItem() {
        val queue = FakeQueue(listOf(item("failed")), 0)
        val cached = item("cached")
        val replacement = listOf(item("online"), cached)
        ErrorRecoveringPlayer(queue.player, offlinePolicy()) {}.setMediaItems(replacement)
        assertEquals(replacement, queue.items)
        assertSame(cached, queue.player.currentMediaItem)
        assertEquals(1, queue.index)
        assertEquals(0L, queue.positionMs)
        assertEquals(1, queue.prepares)
    }

    @Test
    fun anAutomaticTransitionOntoAnUnavailableEntrySkipsItWithoutChangingTheQueue() {
        val cached = item("cached")
        val original = listOf(cached, item("uncached"), item("cached"))
        val queue = FakeQueue(original, 1, state = Player.STATE_READY, error = null)
        assertTrue(skipUnavailableOfflineItem(queue.player, offlinePolicy()))
        assertEquals(2, queue.index)
        assertEquals(original, queue.items)
        assertTrue(queue.removed.isEmpty())
        assertEquals(listOf("seekTo"), queue.calls)
    }

    @Test
    fun connectivityStateNeverPrunesThePhoneQueue() {
        val original = listOf(item("cached"), item("uncached"))
        val playable = FakeQueue(original, 0, state = Player.STATE_READY, error = null)
        assertFalse(skipUnavailableOfflineItem(playable.player, offlinePolicy()))
        val online = FakeQueue(original, 1, state = Player.STATE_READY, error = null)
        assertFalse(skipUnavailableOfflineItem(online.player, AutoOfflineSongs(isOffline = { false })))
        val noSuccessor = FakeQueue(original, 1, state = Player.STATE_READY, error = null)
        assertFalse(skipUnavailableOfflineItem(noSuccessor.player, offlinePolicy()))
        for (queue in listOf(playable, online, noSuccessor)) {
            assertEquals(original, queue.items)
            assertTrue(queue.calls.isEmpty())
        }
    }

    private fun offlinePolicy() = AutoOfflineSongs(isOffline = { true }, isFullyCachedId = { it == "cached" })
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri(id).build()
    private data class Navigation(val from: Int, val to: Int, val positionMs: Long, val seek: Player.() -> Unit)

    /** A command recorder, not a playback engine: assertions cover dispatch, selection and prepare order. */
    private class FakeQueue(
        initial: List<MediaItem>,
        var index: Int,
        var state: Int = Player.STATE_IDLE,
        var error: PlaybackException? = PlaybackException("source", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        var playWhenReady: Boolean = true,
    ) {
        val items = initial.toMutableList()
        private val windowIds = initial.indices.toMutableList()
        var order: List<Int> = initial.indices.toList()
        var shuffle = false
        var repeat = Player.REPEAT_MODE_OFF
        var positionMs = 1_000L
        var onSeek: () -> Unit = {}
        var prepares = 0
        val calls = mutableListOf<String>()
        val removed = mutableListOf<IntRange>()
        private val timeline = object : Timeline() {
            override fun getWindowCount() = items.size
            override fun getPeriodCount() = items.size
            override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window = throw UnsupportedOperationException("Unused window")
            override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period = throw UnsupportedOperationException("Unused period")
            override fun getIndexOfPeriod(uid: Any) = windowIds.indexOf(uid)
            override fun getUidOfPeriod(periodIndex: Int): Any = windowIds[periodIndex]
            override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int = adjacent(windowIndex, true, repeatMode, shuffleModeEnabled)
            override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int = adjacent(windowIndex, false, repeatMode, shuffleModeEnabled)
        }
        private fun adjacent(current: Int, forward: Boolean, mode: Int, shuffled: Boolean): Int {
            val indices = if (shuffled) order.mapNotNull { windowIds.indexOf(it).takeIf { index -> index >= 0 } } else items.indices.toList()
            val at = indices.indexOf(current)
            if (at < 0) return C.INDEX_UNSET
            val destination = at + if (forward) 1 else -1
            return indices.getOrNull(destination) ?: if (mode == Player.REPEAT_MODE_ALL) {
                if (forward) indices.first() else indices.last()
            } else C.INDEX_UNSET
        }
        private fun move(to: Int, position: Long) {
            if (to in items.indices) { index = to; positionMs = if (position == C.TIME_UNSET) 0L else position }
            onSeek()
        }
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            val navigationRepeat = if (repeat == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else repeat
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayerError" -> error
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getCurrentMediaItemIndex" -> index
                "getCurrentPosition" -> positionMs
                "getCurrentTimeline" -> timeline
                "getRepeatMode" -> repeat
                "getShuffleModeEnabled" -> shuffle
                "getNextMediaItemIndex" -> adjacent(index, true, navigationRepeat, shuffle)
                "getPreviousMediaItemIndex" -> adjacent(index, false, navigationRepeat, shuffle)
                "isCurrentMediaItemSeekable" -> true
                "isCurrentMediaItemLive" -> false
                "getMaxSeekToPreviousPosition" -> 3_000L
                "getPlayWhenReady" -> playWhenReady
                "seekToNext", "seekToNextMediaItem" -> { calls += method.name; move(adjacent(index, true, navigationRepeat, shuffle), C.TIME_UNSET); null }
                "seekToPrevious", "seekToPreviousMediaItem" -> { calls += method.name; move(adjacent(index, false, navigationRepeat, shuffle), C.TIME_UNSET); null }
                "seekTo" -> {
                    calls += method.name
                    if (args!!.size == 2) move(args[0] as Int, args[1] as Long) else move(index, args[0] as Long)
                    null
                }
                "seekToDefaultPosition" -> { calls += method.name; move(if (args.isNullOrEmpty()) index else args[0] as Int, C.TIME_UNSET); null }
                "removeMediaItems" -> {
                    calls += method.name
                    val start = args!![0] as Int
                    val end = args[1] as Int
                    removed += start until end
                    items.subList(start, end).clear()
                    windowIds.subList(start, end).clear()
                    index = when { index >= end -> index - (end - start); index >= start -> start.coerceAtMost(items.lastIndex); else -> index }
                    null
                }
                "setMediaItems" -> {
                    calls += method.name
                    @Suppress("UNCHECKED_CAST") val replacement = args!![0] as List<MediaItem>
                    items.clear(); items.addAll(replacement)
                    windowIds.clear(); windowIds.addAll(items.indices)
                    move(args[1] as Int, args[2] as Long)
                    null
                }
                "prepare" -> { calls += method.name; prepares++; state = Player.STATE_BUFFERING; error = null; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "CommandRecordingPlayer"
                else -> throw AssertionError("Unexpected Player.${method.name}")
            }
        } as Player
    }
}
