package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

class SourceAttemptSequenceTest {
    private val request = AudioResolveRequest("media", TrackMatching.Target("Song", listOf("Artist"), null, null))
    private fun attempt(block: suspend () -> PreparedAudio?) = object : AudioProviderRepository {
        override val source = AudioSourceType.QOBUZ
        override suspend fun resolve(request: AudioResolveRequest) = block()
    }
    private fun audio(title: String = "Song") = PreparedAudio.Progressive(
        DirectStream("https://audio.invalid/full", "audio/flac", "flac", null, "Qobuz", AudioSourceType.QOBUZ, title, "Artist"))
    @Test fun preservesHostOrderSkipsBadMetadataAndKeepsPoolSeparate() = runTest {
        val seen = mutableListOf<String>()
        var pool = true
        val candidates = listOf(
            ProviderAttempt(attempt { seen += "personal"; throw ProviderHttpFailure(401) }, false),
            ProviderAttempt(attempt { seen += "pool"; audio("Wrong recording") }, true),
            ProviderAttempt(attempt { seen += "instance"; audio() }, false))
        val sequence = SourceAttemptSequence(AudioSourceType.QOBUZ, { candidates }, { pool })
        assertNotNull(sequence.resolve(request))
        assertEquals(listOf("personal", "pool", "instance"), seen)
        seen.clear(); pool = false
        assertNotNull(sequence.resolve(request))
        assertEquals(listOf("personal", "instance"), seen)
    }
    @Test fun poolRevocationDiscardsInFlightWinnerAndExternalCancellationNeverFallsThrough() = runTest {
        var pool = true
        val sequence = SourceAttemptSequence(AudioSourceType.QOBUZ,
            { listOf(ProviderAttempt(attempt { pool = false; audio() }, true)) }, { pool })
        assertNull(sequence.resolve(request))
        val cancelled = SourceAttemptSequence(AudioSourceType.QOBUZ,
            { listOf(ProviderAttempt(attempt { throw CancellationException("fixture") }, false)) }, { false })
        assertFailsWith<CancellationException> { cancelled.resolve(request) }
    }
}
