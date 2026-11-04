package moe.rukamori.archivetune.shared.data.playback

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AudioOutputFallbackTest {
    @Test
    fun acceptedToneGraphNeverCreatesAnotherOutput() {
        val output = Any()
        val calls = mutableListOf<String>()
        assertSame(output, createAudioOutputWithFallback(
            primary = { calls += "tone"; output },
            plain = { calls += "plain"; Any() },
        ))
        assertEquals(listOf("tone"), calls)
    }

    @Test
    fun refusedToneGraphRetriesPlainExactlyOnceInOrder() {
        val calls = mutableListOf<String>()
        val output = Any()
        assertSame(output, createAudioOutputWithFallback(
            primary = { calls += "tone"; null },
            plain = { calls += "plain"; output },
        ))
        assertEquals(listOf("tone", "plain"), calls)
    }

    @Test
    fun refusedPlainGraphReturnsFailureWithoutAnUnboundedRetry() {
        val calls = mutableListOf<String>()
        assertNull(createAudioOutputWithFallback<Any>(
            primary = { calls += "tone"; null },
            plain = { calls += "plain"; null },
        ))
        assertEquals(listOf("tone", "plain"), calls)
    }

    @Test
    fun unexpectedPrimaryFailurePropagatesWithoutOpeningPlainOutput() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("constructor failed")
        val thrown = assertFailsWith<IllegalStateException> {
            createAudioOutputWithFallback<Any>(
                primary = { calls += "tone"; throw failure },
                plain = { calls += "plain"; Any() },
            )
        }
        assertSame(failure, thrown)
        assertEquals(listOf("tone"), calls)
    }

    @Test
    fun cancellationDuringPlainRetryPropagatesWithoutAnotherAttempt() {
        val calls = mutableListOf<String>()
        val cancellation = CancellationException("constructor cancelled")
        val thrown = assertFailsWith<CancellationException> {
            createAudioOutputWithFallback<Any>(
                primary = { calls += "tone"; null },
                plain = { calls += "plain"; throw cancellation },
            )
        }
        assertSame(cancellation, thrown)
        assertEquals(listOf("tone", "plain"), calls)
    }
}
