package moe.rukamori.archivetune.desktop.integration.auth

import com.sun.jna.NativeLibrary
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import moe.rukamori.archivetune.shared.domain.platform.*
import org.junit.Assume

class DesktopCredentialStoreTest {
    private fun key(scope: CredentialScope = CredentialScope.PERSONAL, provider: String = "spotify", name: String = "token") =
        CredentialKey(provider, scope, name)
    @Test fun allScopesProvidersAndNamesHaveSeparateStableCollisionSafeAccounts() {
        val keys = CredentialScope.entries.flatMap { scope -> listOf(key(scope), key(scope, "other"), key(scope, name = "other")) }
        assertEquals(keys.size, keys.map(::credentialAccount).toSet().size)
        assertEquals(credentialAccount(key()), credentialAccount(key()))
        assertNotEquals(credentialAccount(key(provider = "a:b", name = "c")), credentialAccount(key(provider = "a", name = "b:c")))
    }
    @Test fun adapterUsesM7ScopesAndWipesItsTransientBuffers() = runBlocking<Unit> {
        val backend = ComponentBackend()
        val store = DesktopSecureCredentialStore(backend)
        for (scope in CredentialScope.entries) store.write(key(scope), "$scope:日本語\u0000")
        assertTrue(backend.lastInput!!.all { it == 0.toByte() })
        for (scope in CredentialScope.entries) assertEquals("$scope:日本語\u0000", store.read(key(scope)))
        store.remove(key(CredentialScope.SPOTIFY_STREAMING))
        assertNull(store.read(key(CredentialScope.SPOTIFY_STREAMING)))
        assertNotNull(store.read(key(CredentialScope.SPOTIFY_CATALOG)))
        assertNotNull(store.read(key(CredentialScope.PLAYBACK_POOL)))
    }
    @Test fun unavailableVaultIsAnErrorForEveryOperationNotMissingOrPlaintext() = runBlocking<Unit> {
        val store = DesktopSecureCredentialStore(object : CredentialBackend {
            override suspend fun read(account: String): ByteArray? = throw UnsatisfiedLinkError("absent")
            override suspend fun write(account: String, value: ByteArray): Unit = throw UnsatisfiedLinkError("absent")
            override suspend fun remove(account: String): Unit = throw UnsatisfiedLinkError("absent")
        })
        assertFailsWith<DesktopCredentialStoreUnavailable> { store.read(key()) }
        assertFailsWith<DesktopCredentialStoreUnavailable> { store.write(key(), "private") }
        assertFailsWith<DesktopCredentialStoreUnavailable> { store.remove(key()) }
    }
    @Test fun validatesCredentialSizeAndEncodingWithoutCallingBackend() = runBlocking<Unit> {
        val backend = ComponentBackend()
        val store = DesktopSecureCredentialStore(backend)
        assertFailsWith<IllegalArgumentException> { store.write(key(), "x".repeat(MAX_CREDENTIAL_BYTES + 1)) }
        assertFails { store.write(key(), "\ud800") }
        assertNull(backend.lastInput)
    }
    @Test fun cancellationIsNeverConvertedToUnavailableOrMissing() = runBlocking<Unit> {
        val store = DesktopSecureCredentialStore(object : CredentialBackend {
            override suspend fun read(account: String): ByteArray? = throw CancellationException()
            override suspend fun write(account: String, value: ByteArray): Unit = throw CancellationException()
            override suspend fun remove(account: String): Unit = throw CancellationException()
        })
        assertFailsWith<CancellationException> { store.read(key()) }
        assertFailsWith<CancellationException> { store.write(key(), "x") }
        assertFailsWith<CancellationException> { store.remove(key()) }
    }
    @Test fun dpapiFileComponentWritesOnlyNativeCiphertextAndPreservesOldSlotOnFailure() = runBlocking<Unit> {
        // Tests ONLY the atomic file boundary. These injected bytes are NOT DPAPI/native proof.
        val root = Files.createTempDirectory("archivetune-dpapi-component")
        try {
            val account = credentialAccount(key())
            val cipher = byteArrayOf(0x41, 0x54, 0x43, 0x01)
            var fail = false
            val backend = WindowsDpapiBackend(root, protect = { _, entropy ->
                assertEquals(account, entropy.toString(Charsets.US_ASCII))
                if (fail) throw DesktopCredentialStoreUnavailable()
                cipher.copyOf()
            }, unprotect = { bytes, entropy ->
                assertContentEquals(cipher, bytes)
                assertEquals(account, entropy.toString(Charsets.US_ASCII))
                "decoded-by-native-boundary".toByteArray()
            }, ensureAvailable = {})
            backend.write(account, "private".toByteArray())
            assertContentEquals(cipher, Files.readAllBytes(root.resolve("$account.dpapi")))
            fail = true
            assertFailsWith<DesktopCredentialStoreUnavailable> { backend.write(account, "new".toByteArray()) }
            assertContentEquals(cipher, Files.readAllBytes(root.resolve("$account.dpapi")))
            assertEquals("decoded-by-native-boundary", backend.read(account)!!.toString(Charsets.UTF_8))
            backend.remove(account); backend.remove(account)
            assertNull(backend.read(account))
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun dpapiNativeAbsenceCannotLookLikeAnEmptyVaultOrSuccessfulRemoval() = runBlocking<Unit> {
        val root = Files.createTempDirectory("archivetune-dpapi-unavailable")
        try {
            val backend = WindowsDpapiBackend(root, ensureAvailable = { throw DesktopCredentialStoreUnavailable() })
            val account = credentialAccount(key())
            assertFailsWith<DesktopCredentialStoreUnavailable> { backend.read(account) }
            assertFailsWith<DesktopCredentialStoreUnavailable> { backend.write(account, byteArrayOf(1)) }
            assertFailsWith<DesktopCredentialStoreUnavailable> { backend.remove(account) }
            assertTrue(Files.list(root).use { it.findAny().isEmpty })
        } finally { Files.delete(root) }
    }
    @Test fun thisHostWithoutLibsecretFailsClosed() = runBlocking<Unit> {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val installed = try { NativeLibrary.getInstance("libsecret-1.so.0"); true } catch (_: UnsatisfiedLinkError) { false }
        Assume.assumeFalse("Run real-vault opt-in on provisioned hosts", installed)
        val store = DesktopSecureCredentialStore()
        assertFailsWith<DesktopCredentialStoreUnavailable> { store.read(key()) }
        assertFailsWith<DesktopCredentialStoreUnavailable> { store.write(key(), "ephemeral-test-value") }
        assertFailsWith<DesktopCredentialStoreUnavailable> { store.remove(key()) }
    }
    @Test fun actualOsVaultRoundTripWhenExplicitlyRequested() = runBlocking<Unit> {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("archivetune.desktop.vault.tests"))
        System.getenv("ARCHIVETUNE_SYSTEM_LIBRARY")?.let { System.setProperty("archivetune.system.library", it) }
        val store = DesktopSecureCredentialStore()
        val keys = CredentialScope.entries.map { CredentialKey("archivetune-local-verification", it, UUID.randomUUID().toString()) }
        try {
            for ((index, key) in keys.withIndex()) {
                assertNull(store.read(key))
                store.write(key, "ephemeral-$index-日本語\u0000")
            }
            for ((index, key) in keys.withIndex()) assertEquals("ephemeral-$index-日本語\u0000", store.read(key))
            store.remove(keys[2])
            assertNull(store.read(keys[2]))
            assertNotNull(store.read(keys[1]))
        } finally { keys.forEach { store.remove(it) } }
    }

    private class ComponentBackend : CredentialBackend {
        private val values = mutableMapOf<String, ByteArray>()
        var lastInput: ByteArray? = null
        override suspend fun read(account: String) = values[account]?.copyOf()
        override suspend fun write(account: String, value: ByteArray) { lastInput = value; values[account] = value.copyOf() }
        override suspend fun remove(account: String) { values.remove(account)?.fill(0) }
    }
}
