package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class KuromojiFactoryContractTest {
    @Test fun missingPackHasNoTokenizerAndReadsOnlyBoundedDataNames() = runTest {
        val names = mutableListOf<String>()
        assertNull(KuromojiJapaneseTokenizerFactory.create({ name, max ->
            names.add(name)
            assertEquals("characterDefinitions.bin", name)
            assertEquals(398718, max)
            null
        }))
        assertEquals(listOf("characterDefinitions.bin"), names)
    }

    @Test fun limitsCanOnlyTightenHardCeilings() {
        assertFailsWith<IllegalArgumentException> { KuromojiLimits(maxInputCodeUnits = 16_385) }
        assertFailsWith<IllegalArgumentException> { KuromojiLimits(maxNodes = 100_001) }
        assertFailsWith<IllegalArgumentException> { KuromojiLimits(maxWork = 2_000_001) }
        assertFailsWith<IllegalArgumentException> { KuromojiLimits(maxNodes = 0) }
    }

    @Test fun alreadyCancelledConstructionDoesNotRead() = runTest {
        // A separate scope prevents cancelling the test runner itself.
        val job = kotlinx.coroutines.Job()
        job.cancel()
        assertFailsWith<CancellationException> {
            kotlinx.coroutines.withContext(currentCoroutineContext() + job) {
                KuromojiJapaneseTokenizerFactory.create({ _, _ -> error("Must not read") })
            }
        }
    }

    @Test fun cancellationDuringAMissingReadIsNotDowngradedToNotInstalled() = runTest {
        val job = kotlinx.coroutines.Job()
        var returned = false
        assertFailsWith<CancellationException> {
            kotlinx.coroutines.withContext(currentCoroutineContext() + job) {
                KuromojiJapaneseTokenizerFactory.create({ _, _ ->
                    currentCoroutineContext().cancel()
                    null
                })
                returned = true
            }
        }
        assertFalse(returned)
    }
}
