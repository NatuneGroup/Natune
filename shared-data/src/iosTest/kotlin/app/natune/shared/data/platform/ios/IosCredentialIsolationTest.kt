package app.natune.shared.data.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import app.natune.shared.domain.platform.CredentialKey
import app.natune.shared.domain.platform.CredentialScope

class IosCredentialIsolationTest {
    @Test
    fun catalogStreamingAndPoolNeverShareKeychainAccounts() {
        val accounts = CredentialScope.entries.map {
            IosKeychainCredentialStore.accountId(CredentialKey("spotify", it, "session"))
        }
        assertEquals(CredentialScope.entries.size, accounts.toSet().size)
    }

    @Test
    fun delimiterLikeProviderNamesCannotCollide() {
        assertNotEquals(
            IosKeychainCredentialStore.accountId(CredentialKey("a:PERSONAL", CredentialScope.PERSONAL, "b")),
            IosKeychainCredentialStore.accountId(CredentialKey("a", CredentialScope.PERSONAL, "PERSONAL:b")),
        )
    }
}
