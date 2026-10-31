package moe.rukamori.archivetune.shared.data.lastfm

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmApiConfig
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmApiException
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmAppCredentials
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmBatchResult
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmEndpoints
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmMobileAuthMode
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmScrobble
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmScrobbleStore
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmService
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmSession
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmSessionPort
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTrackMetadata

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LastFmScrobbleOwnerTest {
    private val track = LastFmTrackMetadata("Daft Punk", "One More Time", "Discovery", 320)
    private val config = LastFmApiConfig(LastFmEndpoints.DEFAULT, LastFmAppCredentials("key", "secret"))
    private val session = LastFmSession("listener", "session-key")
    private val startedAt = NOW - 60L

    @Test
    fun aQueuedPlayIsWrittenBeforeTheFirstSend() = runTest {
        val store = FakeStore()
        val service = FakeService(onScrobble = { store.writtenDocuments.size })
        val owner = owner(service = service, store = store)

        owner.enqueue(track, startedAt)
        runCurrent()

        assertEquals(listOf(1), service.observedWriteCounts)
        val sent = service.sent.single()
        assertEquals("Daft Punk", sent.artist)
        assertEquals(startedAt, sent.timestampEpochSeconds)
        assertEquals(320, sent.durationSeconds)
    }

    @Test
    fun aSentPlayLeavesTheQueue() = runTest {
        val store = FakeStore()
        val owner = owner(store = store)

        owner.enqueue(track, startedAt)
        runCurrent()

        assertEquals(0, owner.pendingCount.value)
        assertEquals("[]", store.document)
    }

    @Test
    fun aRetryableFailureKeepsThePlayForLater() = runTest {
        val store = FakeStore()
        val service = FakeService(failure = IllegalArgumentException("network down"))
        val reported = mutableListOf<Exception>()
        val owner = owner(service = service, store = store, onFailure = { reported += it })

        owner.enqueue(track, startedAt)
        runCurrent()

        assertEquals(1, owner.pendingCount.value)
        assertEquals(1, service.attempts)
        // The play is still on disk for the next attempt, and the send failure stayed inside the owner.
        assertTrue(requireNotNull(store.document).contains("One More Time"))
        assertTrue(reported.isEmpty())
    }

    @Test
    fun aRevokedSessionIsClearedAndThePlayStaysQueued() = runTest {
        val sessions = FakeSessions(session)
        val service = FakeService(failure = LastFmApiException(9, "invalid session", httpStatus = 403))
        val owner = owner(service = service, sessions = sessions)

        owner.enqueue(track, startedAt)
        runCurrent()

        assertEquals("session-key", sessions.clearedKey)
        assertEquals(1, owner.pendingCount.value)
    }

    @Test
    fun theSamePlayIsQueuedOnlyOnce() = runTest {
        val service = FakeService(failure = IllegalArgumentException("network down"))
        val owner = owner(service = service)

        owner.enqueue(track, startedAt)
        runCurrent()
        owner.enqueue(track, startedAt)
        runCurrent()

        assertEquals(1, owner.pendingCount.value)
    }

    @Test
    fun withoutASessionNothingIsSentAndNothingIsDropped() = runTest {
        val service = FakeService()
        val owner = owner(service = service, sessions = FakeSessions(null))

        owner.enqueue(track, startedAt)
        runCurrent()

        assertEquals(0, service.attempts)
        assertEquals(1, owner.pendingCount.value)
    }

    @Test
    fun aPlayOlderThanLastFmsWindowIsDropped() = runTest {
        val store = FakeStore()
        val expired = PendingLastFmScrobble(
            artist = "Daft Punk",
            track = "Too Old",
            timestamp = NOW - 15L * 24 * 60 * 60,
            account = "${LastFmEndpoints.DEFAULT}|listener",
        )
        store.document = LastFmScrobbleDocument.encode(listOf(expired))
        val service = FakeService()
        val owner = owner(service = service, store = store)

        owner.enqueue(track, startedAt)
        runCurrent()

        assertEquals(listOf("One More Time"), service.sent.map { it.track })
    }

    private fun TestScope.owner(
        service: FakeService = FakeService(),
        store: FakeStore = FakeStore(),
        sessions: FakeSessions = FakeSessions(session),
        onFailure: (Exception) -> Unit = {},
    ) = LastFmScrobbleOwner(
        scope = backgroundScope,
        service = service,
        sessions = sessions,
        store = store,
        config = { config },
        nowEpochSeconds = { NOW },
        onFailure = onFailure,
    )

    private class FakeSessions(private var session: LastFmSession?) : LastFmSessionPort {
        var clearedKey: String? = null

        override suspend fun currentSession(): LastFmSession? = session

        override suspend fun clearSessionIfCurrent(sessionKeyUsed: String) {
            if (session?.sessionKey == sessionKeyUsed) {
                clearedKey = sessionKeyUsed
                session = null
            }
        }
    }

    private class FakeStore : LastFmScrobbleStore {
        var document: String? = null
        val writtenDocuments = mutableListOf<String>()

        override suspend fun read(): String? = document

        override suspend fun write(document: String) {
            this.document = document
            writtenDocuments += document
        }
    }

    private class FakeService(
        private val failure: Throwable? = null,
        private val onScrobble: () -> Int = { 0 },
    ) : LastFmService {
        var attempts = 0
        val observedWriteCounts = mutableListOf<Int>()
        val sent = mutableListOf<LastFmScrobble>()

        override suspend fun requestWebToken(): Result<String> = Result.failure(unsupported())

        override fun authorizationUrl(token: String): String = "https://example.invalid/?token=$token"

        override suspend fun exchangeWebToken(token: String): Result<LastFmSession> = Result.failure(unsupported())

        override suspend fun signInWithPassword(
            username: String,
            password: String,
            mode: LastFmMobileAuthMode,
        ): Result<LastFmSession> = Result.failure(unsupported())

        override suspend fun updateNowPlaying(track: LastFmTrackMetadata, sessionKey: String): Result<Unit> =
            Result.failure(unsupported())

        override suspend fun scrobbleBatch(
            scrobbles: List<LastFmScrobble>,
            sessionKey: String,
        ): Result<LastFmBatchResult> {
            attempts += 1
            observedWriteCounts += onScrobble()
            val failed = failure
            if (failed != null) return Result.failure(failed)
            sent += scrobbles
            return Result.success(LastFmBatchResult(scrobbles.size, 0, emptyList()))
        }

        override suspend fun setLoved(
            artist: String,
            track: String,
            loved: Boolean,
            sessionKey: String,
        ): Result<Unit> = Result.failure(unsupported())

        private fun unsupported() = UnsupportedOperationException("not used by the scrobble queue")
    }

    private companion object {
        // A fixed wall clock keeps the age window and the sent timestamps decidable.
        const val NOW = 1_700_000_000L
    }
}
