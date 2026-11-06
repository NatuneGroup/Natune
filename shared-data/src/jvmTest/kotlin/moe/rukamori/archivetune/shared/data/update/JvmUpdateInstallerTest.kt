package moe.rukamori.archivetune.shared.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.UpdateOffer
import moe.rukamori.archivetune.shared.domain.update.UpdateDownloadState

/**
 * Digest enforcement while an artifact is staged: the feed's `sha256` decides whether a download ever
 * reaches ReadyToInstall, a feed without one keeps the length-only behaviour, and a truncated body
 * still fails regardless.
 */
class JvmUpdateInstallerTest {

    private val artifact = "pretend-installer-bytes".encodeToByteArray()
    private val artifactUrl = "https://example.test/natune-desktop-linux-x64-15.1.0.deb"

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

    private fun offer(sha256: String?, versionCode: Int = 11) = UpdateOffer(
        channel = UpdateChannel.STABLE,
        tag = "v15.1.0",
        versionCode = versionCode,
        versionName = "15.1.0",
        notes = null,
        downloadUrl = artifactUrl,
        sha256 = sha256,
    )

    private fun tempDir(): Path = Files.createTempDirectory("natune-update-digest-test")

    private suspend fun awaitTerminal(installer: JvmUpdateInstaller): UpdateDownloadState =
        withTimeout(10_000L) {
            installer.state.first {
                it is UpdateDownloadState.ReadyToInstall || it is UpdateDownloadState.Failed
            }
        }

    @Test
    fun matchingDigestStagesTheArtifact() = runBlocking {
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(MockEngine { respond(artifact) })
        val installer = JvmUpdateInstaller(client, dir, installedCode = 10, scope = scope, nowMs = { 0L })
        try {
            assertTrue(installer.startDownload(offer(sha256Hex(artifact))))
            val state = awaitTerminal(installer)
            assertIs<UpdateDownloadState.ReadyToInstall>(state)
            assertFalse(state.reused)
            assertTrue(Files.exists(dir.resolve("natune-update.deb")))
            assertEquals(artifactUrl, Files.readString(dir.resolve("natune-update.source")))
            assertEquals("11", Files.readString(dir.resolve("natune-update.code")))
            assertFalse(Files.exists(dir.resolve("natune-update.download")))
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun mismatchingDigestDeletesTheStagedDownloadAndFails() = runBlocking {
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(MockEngine { respond(artifact) })
        val installer = JvmUpdateInstaller(client, dir, installedCode = 10, scope = scope, nowMs = { 0L })
        try {
            assertTrue(installer.startDownload(offer("0".repeat(64))))
            val state = awaitTerminal(installer)
            assertIs<UpdateDownloadState.Failed>(state)
            assertTrue(state.message.orEmpty().contains("digest", ignoreCase = true))
            // Nothing unverified survives: no partial download, no staged artifact, no sidecars.
            assertFalse(Files.exists(dir.resolve("natune-update.download")))
            assertFalse(Files.exists(dir.resolve("natune-update.deb")))
            assertFalse(Files.exists(dir.resolve("natune-update.source")))
            assertFalse(Files.exists(dir.resolve("natune-update.code")))
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun feedWithoutADigestKeepsTheLengthOnlyPath() = runBlocking {
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(MockEngine { respond(artifact) })
        val installer = JvmUpdateInstaller(client, dir, installedCode = 10, scope = scope, nowMs = { 0L })
        try {
            assertTrue(installer.startDownload(offer(sha256 = null)))
            val state = awaitTerminal(installer)
            assertIs<UpdateDownloadState.ReadyToInstall>(state)
            assertEquals(artifact.size.toLong(), Files.size(dir.resolve("natune-update.deb")))
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun truncatedBodyFailsEvenWithAMatchingDigest() = runBlocking {
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(MockEngine {
            respond(
                ByteReadChannel(artifact),
                headers = headersOf(HttpHeaders.ContentLength, (artifact.size + 32).toString()),
            )
        })
        val installer = JvmUpdateInstaller(client, dir, installedCode = 10, scope = scope, nowMs = { 0L })
        try {
            assertTrue(installer.startDownload(offer(sha256Hex(artifact))))
            val state = awaitTerminal(installer)
            assertIs<UpdateDownloadState.Failed>(state)
            assertTrue(state.message.orEmpty().contains("Truncated"))
            assertFalse(Files.exists(dir.resolve("natune-update.download")))
            assertFalse(Files.exists(dir.resolve("natune-update.deb")))
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun stagedArtifactIsReverifiedAgainstTheDigestBeforeReuse() = runBlocking {
        val dir = tempDir()
        // A previous run left an artifact staged with matching provenance, but its bytes cannot match
        // the digest the feed now publishes.
        Files.write(dir.resolve("natune-update.deb"), "an-older-unverified-download".toByteArray())
        Files.writeString(dir.resolve("natune-update.source"), artifactUrl)
        Files.writeString(dir.resolve("natune-update.code"), "11")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var requests = 0
        val client = HttpClient(MockEngine {
            requests += 1
            respond(artifact)
        })
        val installer = JvmUpdateInstaller(client, dir, installedCode = 10, scope = scope, nowMs = { 0L })
        try {
            assertTrue(installer.startDownload(offer(sha256Hex(artifact))))
            val state = awaitTerminal(installer)
            assertIs<UpdateDownloadState.ReadyToInstall>(state)
            assertFalse(state.reused)
            assertEquals(1, requests)
            assertEquals(artifact.size.toLong(), Files.size(dir.resolve("natune-update.deb")))
        } finally {
            scope.cancel()
            client.close()
        }
    }
}
