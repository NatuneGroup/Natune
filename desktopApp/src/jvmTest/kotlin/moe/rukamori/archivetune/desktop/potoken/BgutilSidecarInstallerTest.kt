package moe.rukamori.archivetune.desktop.potoken

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BgutilSidecarInstallerTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun verifiesAndInstallsThePinnedAsset() = runBlocking {
        val root = newRoot()
        val payload = elfPayload()
        val spec = specFor(payload, sha256(payload))
        val installer =
            BgutilSidecarInstaller(
                root = root,
                download = { _, target -> Files.write(target, payload) },
            )

        val resolved = assertNotNull(installer.resolve(spec))

        assertContentEquals(payload, Files.readAllBytes(resolved))
        assertTrue(Files.isExecutable(resolved))
        assertEquals(root.resolve(spec.binaryFileName), resolved)
        assertTrue(Files.notExists(root.resolve("${spec.assetName}.download")))
    }

    @Test
    fun refusesAChecksumMismatchAndRemovesThePartialFile() = runBlocking {
        val root = newRoot()
        val payload = elfPayload()
        val spec = specFor(payload, "00".repeat(32))
        val installer =
            BgutilSidecarInstaller(
                root = root,
                download = { _, target -> Files.write(target, payload) },
            )

        assertNull(installer.resolve(spec))

        assertTrue(Files.notExists(root.resolve(spec.binaryFileName)))
        assertTrue(Files.notExists(root.resolve("${spec.assetName}.download")))
    }

    @Test
    fun reusesAStillValidCacheWithoutDownloading() = runBlocking {
        val root = newRoot()
        val payload = elfPayload()
        val spec = specFor(payload, sha256(payload))
        BgutilSidecarInstaller(root, download = { _, target -> Files.write(target, payload) }).resolve(spec)

        val offline =
            BgutilSidecarInstaller(
                root = root,
                download = { _, _ -> throw IllegalStateException("must not download") },
            )

        assertEquals(root.resolve(spec.binaryFileName), offline.resolve(spec))
    }

    @Test
    fun replacesACacheThatNoLongerMatchesThePin() = runBlocking {
        val root = newRoot()
        val payload = elfPayload()
        val spec = specFor(payload, sha256(payload))
        Files.createDirectories(root)
        Files.write(root.resolve(spec.binaryFileName), byteArrayOf(1, 2, 3, 4))

        val resolved =
            BgutilSidecarInstaller(root, download = { _, target -> Files.write(target, payload) }).resolve(spec)

        assertContentEquals(payload, Files.readAllBytes(assertNotNull(resolved)))
    }

    @Test
    fun rejectsANonNativeDownload() = runBlocking {
        val root = newRoot()
        val payload = "<html>rate limited</html>".toByteArray()
        val spec = specFor(payload, sha256(payload))
        val installer = BgutilSidecarInstaller(root, download = { _, target -> Files.write(target, payload) })

        assertNull(installer.resolve(spec))
        assertTrue(Files.notExists(root.resolve(spec.binaryFileName)))
    }

    private fun newRoot(): Path = Files.createTempDirectory("potoken-installer-test").also { roots += it }

    private fun specFor(payload: ByteArray, digest: String): BgutilSidecarSpec =
        BgutilSidecarSpec(
            platform = BgutilHostPlatform.LINUX,
            arch = BgutilHostArch.X64,
            assetName = "bgutil-pot-test",
            sha256 = digest,
            binaryFileName = "bgutil-pot-test",
            assetBytes = payload.size.toLong(),
        )

    private fun elfPayload(): ByteArray = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00, 0x10)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
}
