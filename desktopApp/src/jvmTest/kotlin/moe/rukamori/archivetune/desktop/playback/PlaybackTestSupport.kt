package moe.rukamori.archivetune.desktop.playback

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import kotlin.test.fail

private const val AWAIT_TIMEOUT_MS = 10_000L
private const val POLL_INTERVAL_MS = 10L
private const val NANOS_PER_MILLI = 1_000_000L

/** Polls [read] until it returns a value. Fails the test if [description] is not met in time. */
internal fun <T : Any> awaitValue(description: String, read: () -> T?): T {
    val deadline = System.nanoTime() + AWAIT_TIMEOUT_MS * NANOS_PER_MILLI
    while (true) {
        read()?.let { return it }
        if (System.nanoTime() > deadline) {
            fail("timed out after $AWAIT_TIMEOUT_MS ms waiting for $description")
        }
        Thread.sleep(POLL_INTERVAL_MS)
    }
}

/** The first published snapshot that satisfies [predicate]. Fails the test with the last snapshot seen. */
internal fun PlaybackPort.awaitSnapshot(description: String, predicate: (PlaybackSnapshot) -> Boolean): PlaybackSnapshot {
    val deadline = System.nanoTime() + AWAIT_TIMEOUT_MS * NANOS_PER_MILLI
    while (true) {
        snapshots.value?.let { if (predicate(it)) return it }
        if (System.nanoTime() > deadline) {
            fail("timed out after $AWAIT_TIMEOUT_MS ms waiting for $description; last snapshot ${snapshots.value}")
        }
        Thread.sleep(POLL_INTERVAL_MS)
    }
}

/**
 * Records every failure a port publishes from the moment it is created. A [PlaybackPort.failures]
 * flow keeps no history, so the recorder subscribes before the test issues any command.
 */
internal class FailureRecorder(port: PlaybackPort) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val received = CopyOnWriteArrayList<PlaybackFailure>()

    init {
        // UNDISPATCHED registers the subscriber before this constructor returns, so the test cannot
        // race ahead of it and miss the first failure.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { port.failures.collect { received += it } }
    }

    val all: List<PlaybackFailure> get() = received.toList()

    fun awaitFirst(): PlaybackFailure = awaitValue("a playback failure") { received.firstOrNull() }

    /** Waits until at least [count] failures have arrived, in the order they were published. */
    fun awaitCount(count: Int): List<PlaybackFailure> =
        awaitValue("$count playback failures") { received.takeIf { it.size >= count }?.toList() }

    override fun close() {
        scope.cancel()
    }
}

internal fun localRequest(occurrenceId: String, fixture: String): PlaybackRequest =
    PlaybackRequest(
        occurrenceId = occurrenceId,
        mediaId = "media-$fixture",
        locator = PlaybackLocator.LocalFile(PlaybackFixtures.localPath(fixture)),
    )

internal fun remoteRequest(occurrenceId: String, url: String): PlaybackRequest =
    PlaybackRequest(
        occurrenceId = occurrenceId,
        mediaId = "media-${url.substringAfterLast('/')}",
        locator = PlaybackLocator.Remote(url),
    )
