package moe.rukamori.archivetune.discord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class DiscordSessionRulesTest {
    private val expiresAt = 1_000_000_000L
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMain() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun releaseMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun renewalStartsOneSkewWindowBeforeExpiry() {
        val skew = DiscordSessionRules.EXPIRY_SKEW_MS

        assertFalse(DiscordSessionRules.isRenewalDue(expiresAt, expiresAt - skew - 1))
        assertTrue(DiscordSessionRules.isRenewalDue(expiresAt, expiresAt - skew))
        assertTrue(DiscordSessionRules.isRenewalDue(expiresAt, expiresAt + 1))
    }

    @Test
    fun tokenWithoutRecordedExpiryIsNotRenewed() {
        assertFalse(DiscordSessionRules.isRenewalDue(0L, Long.MAX_VALUE / 2))
    }

    @Test
    fun grantedRefreshTokenReplacesTheUsedOne() {
        assertEquals("new", DiscordSessionRules.refreshTokenToKeep("new", "old"))
    }

    @Test
    fun refreshWithoutRefreshTokenKeepsTheUsedOne() {
        assertEquals("old", DiscordSessionRules.refreshTokenToKeep(null, "old"))
        assertEquals("old", DiscordSessionRules.refreshTokenToKeep("  ", "old"))
    }

    @Test
    fun noRefreshTokenWhenNeitherIsUsable() {
        assertNull(DiscordSessionRules.refreshTokenToKeep(null, null))
        assertNull(DiscordSessionRules.refreshTokenToKeep(" ", " "))
    }

    @Test
    fun refusedGrantAndMissingRefreshTokenNeedAuthorization() {
        assertTrue(DiscordSessionRules.needsAuthorization(DiscordOAuthHttpException(400)))
        assertTrue(DiscordSessionRules.needsAuthorization(DiscordOAuthHttpException(401)))
        assertTrue(DiscordSessionRules.needsAuthorization(DiscordOAuthHttpException(403)))
        assertTrue(DiscordSessionRules.needsAuthorization(DiscordAuthorizationRequiredException()))
    }

    @Test
    fun transientFailuresKeepTheSavedSession() {
        assertFalse(DiscordSessionRules.needsAuthorization(DiscordOAuthHttpException(429)))
        assertFalse(DiscordSessionRules.needsAuthorization(DiscordOAuthHttpException(503)))
        assertFalse(DiscordSessionRules.needsAuthorization(IOException("offline")))
        assertFalse(DiscordSessionRules.needsAuthorization(IllegalStateException("SDK_TOKEN_REFRESH_FAILED")))
        assertFalse(DiscordSessionRules.needsAuthorization(null))
    }

    @Test
    fun refreshCatchingReturnsValueOrFailure() {
        assertEquals("token", refreshCatching { "token" }.getOrNull())
        assertTrue(refreshCatching<Unit> { throw IOException("offline") }.isFailure)
    }

    @Test
    fun refreshCatchingTurnsSdkTimeoutIntoFailure() {
        val timeout = sdkTimeout()

        val result = refreshCatching<Unit> { throw timeout }

        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test
    fun refreshCatchingKeepsCallerCancellation() {
        try {
            refreshCatching<Unit> { throw CancellationException("caller cancelled") }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertFalse(expected is TimeoutCancellationException)
        }
    }

    @Test
    fun closingWithPendingRefreshFailsItSoRenewalFallsBackToHttp() = runTest(dispatcher) {
        val pendingRefresh = CompletableDeferred<DiscordSdkTokenGrant>()
        DiscordNativeSdkBridge.grants[1L] = pendingRefresh

        DiscordNativeSdkBridge.close()

        // Close settles the waiting refresh at once, so renewal does not wait out the SDK timeout.
        assertTrue(pendingRefresh.isCompleted)
        val closed = runCatching { pendingRefresh.await() }.exceptionOrNull()
        // A cancellation would be rethrown by refreshCatching and never reach the HTTP fallback.
        assertFalse(closed is CancellationException)
        assertEquals("SDK_CLOSED", closed?.message)

        val renewal = refreshCatching { pendingRefresh.await() }
        assertTrue(renewal.isFailure)
        // A closed SDK is not a refused grant, so the saved session is kept rather than shown as expired.
        assertFalse(DiscordSessionRules.needsAuthorization(renewal.exceptionOrNull()))
    }

    private fun sdkTimeout(): TimeoutCancellationException {
        try {
            runBlocking { withTimeout(1L) { delay(60_000L) } }
        } catch (timeout: TimeoutCancellationException) {
            return timeout
        }
        throw AssertionError("timeout expected")
    }
}
