package moe.rukamori.archivetune.shared.data.bytesource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteSourceCookieScopeTest {
    private val apiOrigin = StreamOrigin("https", "api.example", 443)
    private val aOrigin = StreamOrigin("https", "a.example", 443)
    private val bOrigin = StreamOrigin("https", "b.example", 443)
    private val accountA = ByteSourceAccount("tidal", "acct-a")
    private val accountB = ByteSourceAccount("tidal", "acct-b")

    private fun lease(
        account: ByteSourceAccount,
        primary: StreamOrigin,
        permitted: Set<StreamOrigin>,
    ): ByteSourceLease =
        ByteSourceLease(
            account = account,
            primaryOrigin = primary,
            permittedOrigins = permitted,
            cacheKey = "k",
            expiresAtEpochMs = null,
            lengthBytes = null,
        )

    private fun cookie(
        account: ByteSourceAccount,
        origin: StreamOrigin,
    ) = ScopedCookie(account, origin, "session", "v")

    @Test
    fun cookieDoesNotLeakAcrossAccountsOnSameOrigin() {
        val leaseB = lease(accountB, apiOrigin, setOf(apiOrigin))
        val cookies = LeaseCookiePolicy.cookiesFor(leaseB, apiOrigin, listOf(cookie(accountA, apiOrigin)))
        assertTrue(cookies.isEmpty())
    }

    @Test
    fun cookieDoesNotLeakAcrossOriginsEvenWhenBothPermitted() {
        val lease = lease(accountA, aOrigin, setOf(aOrigin, bOrigin))
        val cookies = LeaseCookiePolicy.cookiesFor(lease, bOrigin, listOf(cookie(accountA, aOrigin)))
        assertTrue(cookies.isEmpty())
    }

    @Test
    fun matchingAccountAndOriginIsReturned() {
        val lease = lease(accountA, apiOrigin, setOf(apiOrigin))
        val stored = listOf(cookie(accountA, apiOrigin), cookie(accountB, apiOrigin))
        assertEquals(listOf(cookie(accountA, apiOrigin)), LeaseCookiePolicy.cookiesFor(lease, apiOrigin, stored))
    }

    @Test
    fun noCookiesForOriginOutsidePermittedSet() {
        val lease = lease(accountA, apiOrigin, setOf(apiOrigin))
        val cookies = LeaseCookiePolicy.cookiesFor(lease, bOrigin, listOf(cookie(accountA, bOrigin)))
        assertTrue(cookies.isEmpty())
        val sameOriginNotPermitted = LeaseCookiePolicy.cookiesFor(lease, apiOrigin, listOf(cookie(accountA, apiOrigin)))
        assertEquals(1, sameOriginNotPermitted.size)
    }

    @Test
    fun redirectRules() {
        val lease = lease(accountA, aOrigin, setOf(aOrigin, bOrigin))
        val outside = StreamOrigin("https", "evil.example", 443)
        assertEquals(RedirectDecision(false, false), LeaseCookiePolicy.redirect(lease, aOrigin, outside))
        assertEquals(RedirectDecision(true, true), LeaseCookiePolicy.redirect(lease, aOrigin, aOrigin))
        assertEquals(RedirectDecision(true, false), LeaseCookiePolicy.redirect(lease, aOrigin, bOrigin))
        val httpSameHost = StreamOrigin("http", "a.example", 80)
        assertEquals(RedirectDecision(false, false), LeaseCookiePolicy.redirect(lease, aOrigin, httpSameHost))
    }
}
