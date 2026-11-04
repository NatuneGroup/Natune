package moe.rukamori.archivetune.shared.data.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AudioOutputFallbackTest {
    @Test
    fun acceptedToneGraphNeverCreatesAnotherOutput() {
        val output = Any()
        var calls = 0
        assertSame(output, createAudioOutputWithFallback(primary = { output }, plain = { calls++; Any() }))
        assertEquals(0, calls)
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
}
