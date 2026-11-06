package moe.rukamori.archivetune.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicTokenRefreshTest {
    private val nowSec = 1_000_000L
    private val marginSec = 24L * 60L * 60L

    @Test
    fun aQueuedRefreshReusesTheTokenAnotherCallerJustInstalled() {
        assertEquals("fresh", AppleMusicProvider.reusableRefreshedToken("fresh", nowSec + marginSec, "rejected", nowSec))
    }

    @Test
    fun theTokenTheCallerFoundDeadIsNeverHandedBack() {
        assertNull(AppleMusicProvider.reusableRefreshedToken("rejected", nowSec + marginSec, "rejected", nowSec))
    }

    @Test
    fun aTokenInsideTheRefreshMarginIsNotReused() {
        assertNull(AppleMusicProvider.reusableRefreshedToken("almost", nowSec + marginSec - 1, "rejected", nowSec))
        assertNull(AppleMusicProvider.reusableRefreshedToken(null, nowSec + marginSec, null, nowSec))
    }
}
