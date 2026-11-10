package moe.rukamori.archivetune.tidal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalRefreshOutcomeTest {
    @Test
    fun aRefusedGrantNeedsSignIn() {
        assertTrue(TidalRefreshOutcome.isRejection(400))
        assertTrue(TidalRefreshOutcome.isRejection(401))
    }

    @Test
    fun serverAndRateLimitFailuresKeepTheSession() {
        listOf(403, 429, 500, 502, 503).forEach { code -> assertFalse("$code", TidalRefreshOutcome.isRejection(code)) }
    }
}
