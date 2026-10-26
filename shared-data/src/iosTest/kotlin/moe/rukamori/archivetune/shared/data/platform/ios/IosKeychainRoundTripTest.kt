/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.platform.*
import platform.Foundation.NSUUID

/** Actual Apple Keychain test, not a dictionary fake. Unrun without an Apple test host. */
class IosKeychainRoundTripTest {
    @Test
    fun updateAndDeletionStayWithinTheSelectedAccountPurpose() = runBlocking {
        val store = IosKeychainCredentialStore("moe.rukamori.archivetune.test.${NSUUID().UUIDString}")
        val catalog = CredentialKey("spotify", CredentialScope.SPOTIFY_CATALOG, "fixture")
        val stream = catalog.copy(scope = CredentialScope.SPOTIFY_STREAMING)
        try {
            assertNull(store.read(catalog))
            store.write(catalog, "catalog-fixture")
            store.write(stream, "stream-fixture")
            store.write(catalog, "")
            assertEquals("", store.read(catalog))
            assertEquals("stream-fixture", store.read(stream))
            store.remove(catalog)
            assertNull(store.read(catalog))
            assertEquals("stream-fixture", store.read(stream))
        } finally {
            store.remove(catalog)
            store.remove(stream)
        }
    }
}
