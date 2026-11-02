package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Premium detection contract: every paid plan reports "premium", an absent read stays unknown
 * instead of guessing, re-checks are bounded by the TTL on the token-refresh path, and a stored Librespot
 * choice degrades to Unofficial while the bound account is not Premium.
 */
class SpotifyPremiumPolicyTest {
    @Test
    fun premiumProductStringsReadAsPremium() {
        assertEquals(SpotifyProduct.PREMIUM, SpotifyProduct.of("premium"))
        assertEquals(SpotifyProduct.PREMIUM, SpotifyProduct.of("Premium"))
    }

    @Test
    fun freeOpenAndLapsedProductsReadAsFree() {
        assertEquals(SpotifyProduct.FREE, SpotifyProduct.of("free"))
        assertEquals(SpotifyProduct.FREE, SpotifyProduct.of("open"))
        assertEquals(SpotifyProduct.FREE, SpotifyProduct.of("daypass"))
    }

    @Test
    fun missingOrBlankProductsStayUnknown() {
        assertEquals(SpotifyProduct.UNKNOWN, SpotifyProduct.of(null))
        assertEquals(SpotifyProduct.UNKNOWN, SpotifyProduct.of(""))
        assertEquals(SpotifyProduct.UNKNOWN, SpotifyProduct.of("  "))
    }

    @Test
    fun onlyAnObservedPremiumAllowsLibrespot() {
        assertTrue(SpotifyPremiumPolicy.librespotAllowed(SpotifyProduct.PREMIUM))
        assertFalse(SpotifyPremiumPolicy.librespotAllowed(SpotifyProduct.FREE))
        assertFalse(SpotifyPremiumPolicy.librespotAllowed(SpotifyProduct.UNKNOWN))
    }

    @Test
    fun aMissingObservationAlwaysRechecks() {
        assertTrue(SpotifyPremiumPolicy.recheckDue(SpotifyProduct.UNKNOWN, 0L, 1000L, tokenJustMinted = false))
        assertTrue(SpotifyPremiumPolicy.recheckDue(SpotifyProduct.PREMIUM, 0L, 1000L, tokenJustMinted = true))
    }

    @Test
    fun aFreshObservationDoesNotRecheck() {
        val now = 1_000_000L
        assertFalse(
            SpotifyPremiumPolicy.recheckDue(SpotifyProduct.PREMIUM, now - 1000L, now, tokenJustMinted = true),
        )
        assertFalse(
            SpotifyPremiumPolicy.recheckDue(SpotifyProduct.FREE, now - 1000L, now, tokenJustMinted = true),
        )
    }

    @Test
    fun aStaleObservationRechecksOnlyOnTokenRefresh() {
        val now = SpotifyPremiumPolicy.PRODUCT_RECHECK_TTL_MS + 1_000_000L
        val stale = now - SpotifyPremiumPolicy.PRODUCT_RECHECK_TTL_MS - 1L
        assertTrue(SpotifyPremiumPolicy.recheckDue(SpotifyProduct.PREMIUM, stale, now, tokenJustMinted = true))
        assertFalse(SpotifyPremiumPolicy.recheckDue(SpotifyProduct.PREMIUM, stale, now, tokenJustMinted = false))
    }

    @Test
    fun aStoredLibrespotHoldsWhileTheBoundAccountIsPremium() {
        assertEquals(
            SpotifyStreamEngine.LIBRESPOT,
            SpotifyPremiumPolicy.effectiveEngine(SpotifyStreamEngine.LIBRESPOT, SpotifyProduct.PREMIUM),
        )
    }

    @Test
    fun aStoredLibrespotDegradesToUnofficialWithoutPremium() {
        assertEquals(
            SpotifyStreamEngine.UNOFFICIAL,
            SpotifyPremiumPolicy.effectiveEngine(SpotifyStreamEngine.LIBRESPOT, SpotifyProduct.FREE),
        )
        assertEquals(
            SpotifyStreamEngine.UNOFFICIAL,
            SpotifyPremiumPolicy.effectiveEngine(SpotifyStreamEngine.LIBRESPOT, SpotifyProduct.UNKNOWN),
        )
    }

    @Test
    fun unofficialNeverUpgradesItself() {
        assertEquals(
            SpotifyStreamEngine.UNOFFICIAL,
            SpotifyPremiumPolicy.effectiveEngine(SpotifyStreamEngine.UNOFFICIAL, SpotifyProduct.PREMIUM),
        )
    }
}
