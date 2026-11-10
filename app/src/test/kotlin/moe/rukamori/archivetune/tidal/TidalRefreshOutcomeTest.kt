package moe.rukamori.archivetune.tidal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalRefreshOutcomeTest {
    @Test
    fun aRefusedGrantNeedsSignIn() {
        assertTrue(TidalRefreshOutcome.isRejection(400))
        assertTrue(TidalRefreshOutcome.isRejection(401))
        assertTrue(TidalRefreshOutcome.failed(400).rejected)
        assertTrue(TidalRefreshOutcome.failed(401).rejected)
        assertNull(TidalRefreshOutcome.failed(401).token)
    }

    @Test
    fun serverAndRateLimitFailuresKeepTheSession() {
        listOf(403, 429, 500, 502, 503).forEach { code ->
            assertFalse("$code", TidalRefreshOutcome.isRejection(code))
            assertFalse("$code", TidalRefreshOutcome.failed(code).rejected)
        }
    }

    @Test
    fun everyAttemptKeepsItsOwnOutcome() {
        // The refusal belongs to the attempt that saw it; a later failure must not inherit it.
        val refused = TidalRefreshOutcome.failed(401)
        val laterTimeout = TidalRefreshOutcome.failed(504)
        assertTrue(refused.rejected)
        assertFalse(laterTimeout.rejected)
        assertNull(laterTimeout.token)
    }
}
