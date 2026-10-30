package moe.rukamori.archivetune.desktop.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.bytesource.*
import moe.rukamori.archivetune.shared.domain.platform.*

internal object EmptySourceAccounts : AcceptedAccountReader, ScopedCookieSource {
    override suspend fun acceptedAccount(provider: String, accountScope: String): AcceptedAccount? = null
    override suspend fun storedCookies(account: ByteSourceAccount, origin: StreamOrigin): List<ScopedCookie> = emptyList()
    fun admissions() = DesktopSourceAdmissions(this, this)
}

class DesktopSourceAdmissionsTest {
    @Test fun scopesNameOneVerifiedCredentialAndRemovalNeverRevivesItsAdmission() = runTest {
        val admissions = EmptySourceAccounts.admissions()
        val identity = ProviderIdentity("qobuz", CredentialScope.PERSONAL, "real-user")
        var credential = "first"
        val first = assertNotNull(admissions.accept(identity, credential) { credential == "first" })
        assertSame(first, admissions.accept(identity, "first") { credential == "first" })
        assertEquals(identity, assertNotNull(admissions.admit("qobuz", first.scope)).accepted.identity)
        assertNull(admissions.admit("spotify", first.scope))
        credential = "second"
        assertFalse(admissions.active(first))
        assertNull(admissions.admit("qobuz", first.scope))
        credential = "first"
        assertNull(admissions.admit("qobuz", first.scope))
        credential = "second"
        val second = assertNotNull(admissions.accept(identity, credential) { credential == "second" })
        assertNotEquals(first.scope, second.scope)
        assertTrue(second.account.generation > first.account.generation)
        admissions.close()
        assertNull(admissions.admit("qobuz", second.scope))
        assertNull(admissions.accept(identity, "third") { true })
    }

    @Test fun runtimeCdnScopesNeverReadBrowserCookiesButPersistedScopesStillDelegate() = runTest {
        var reads = 0
        val cookies = object : ScopedCookieSource {
            override suspend fun storedCookies(account: ByteSourceAccount, origin: StreamOrigin): List<ScopedCookie> {
                reads++
                return listOf(ScopedCookie(account, origin, "session", "fixture"))
            }
        }
        val admissions = DesktopSourceAdmissions(EmptySourceAccounts, cookies)
        val lease = assertNotNull(admissions.accept(ProviderIdentity("spotify", CredentialScope.SPOTIFY_STREAMING,
            "stream-user"), "stream-cookie") { true })
        val origin = StreamOrigin("https", "cdn.invalid", 443)
        assertTrue(admissions.storedCookies(ByteSourceAccount("spotify", "stream-user", lease.scope), origin).isEmpty())
        assertEquals(0, reads)
        assertEquals(1, admissions.storedCookies(ByteSourceAccount("tidal", "personal-user", "PERSONAL"), origin).size)
        assertEquals(1, reads)
    }
}
