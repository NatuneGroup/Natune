package moe.rukamori.archivetune.shared.data.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.settings.UpdateOffer
import moe.rukamori.archivetune.shared.domain.settings.archiveMatchesOffer
import moe.rukamori.archivetune.shared.domain.update.UpdateAttemptOwner
import moe.rukamori.archivetune.shared.domain.update.UpdateDownloadState
import java.awt.Desktop
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** What [JvmUpdateInstaller.install] did with a staged artifact. */
enum class UpdateInstallOutcome { OPENED, REVEALED }

/** Artifacts the OS can launch directly; everything else is revealed in its folder for manual install. */
private val LAUNCHABLE_EXTENSIONS = setOf("msi", "exe", "dmg", "pkg", "appimage")

/** Feed digests arrive as bare hex or `sha256:<hex>`; blank or absent means the feed published none. */
private fun normalizeSha256(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    // `sha256:<hex>` keeps only the digest; a bare digest has no separator and is used as-is.
    val hex = trimmed.substringAfter(':', trimmed).trim().lowercase()
    return hex.ifEmpty { null }
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }

/** SHA-256 of a file already on disk, or null when it cannot be read. */
private fun sha256HexOf(path: Path): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().toHexString()
}.getOrNull()

/**
 * Desktop port of `AppUpdateInstaller`: one artifact download at a time through [UpdateAttemptOwner],
 * staging under `<updateDir>` (`artifact.download` in flight, `artifact.<ext>` when complete, plus
 * `.source`/`.code` sidecars recording provenance), reuse when the sidecar matches a still-newer offer,
 * 256KB streaming with a length check, and a platform-native install action — the OS handler opens
 * launchable packages (msi/exe/dmg/pkg/AppImage); other archives are revealed for the user to install.
 * When the offer carries the feed's `sha256` the stream is hashed as it is written and a mismatch throws
 * for the existing Failed state; a feed without a digest keeps the length-only behaviour and says so.
 */
class JvmUpdateInstaller(
    private val client: HttpClient,
    private val updateDir: Path,
    private val installedCode: Int,
    scope: CoroutineScope,
    private val nowMs: () -> Long,
) {
    private val attempts = UpdateAttemptOwner(scope, UpdateDownloadState.Idle as UpdateDownloadState)
    val state = attempts.stateFlow

    private val downloadPath get() = updateDir.resolve("natune-update.download")
    private fun stagedPath(extension: String) = updateDir.resolve("natune-update.$extension")
    private val sourcePath get() = updateDir.resolve("natune-update.source")
    private val codePath get() = updateDir.resolve("natune-update.code")

    fun isActive(): Boolean = attempts.isActive()

    fun startDownload(offer: UpdateOffer): Boolean =
        attempts.start(UpdateDownloadState.Downloading(null, offer)) {
            try {
                val staged = stagedFor(offer)
                if (staged != null) {
                    attempts.update(UpdateDownloadState.ReadyToInstall(reused = true, offer = offer))
                    return@start
                }
                download(offer)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                attempts.update(UpdateDownloadState.Failed(failure.message ?: failure.javaClass.simpleName, offer))
            }
        }

    fun cancelDownload() = attempts.cancel(UpdateDownloadState.Idle)

    /** Drop ReadyToInstall/Failed back to Idle once the outcome was consumed. */
    fun acknowledgeResult() = attempts.acknowledge { it !is UpdateDownloadState.Downloading }

    /** A staged artifact from a previous offer whose provenance and build code still match. */
    private suspend fun stagedFor(offer: UpdateOffer): Path? = withContext(Dispatchers.IO) {
        val source = runCatching { Files.readString(sourcePath).trim() }.getOrNull()
        val code = runCatching { Files.readString(codePath).trim().toLong() }.getOrNull()
        val artifact = runCatching {
            Files.list(updateDir).use { stream ->
                stream.filter { it.fileName.toString().startsWith("natune-update.") }
                    .filter { it.fileName.toString() != "natune-update.download" }
                    .filter { it.fileName.toString() != "natune-update.source" }
                    .filter { it.fileName.toString() != "natune-update.code" }
                    .findFirst().orElse(null)
            }
        }.getOrNull() ?: return@withContext null
        if (!archiveMatchesOffer(offer.versionCode, code, installedCode, source == offer.downloadUrl)) {
            return@withContext null
        }
        val expectedDigest = normalizeSha256(offer.sha256) ?: return@withContext artifact
        if (sha256HexOf(artifact) == expectedDigest) return@withContext artifact
        // A reused artifact has to prove the digest too; an unprovable one is discarded, never installed.
        runCatching { Files.deleteIfExists(artifact) }
        runCatching { Files.deleteIfExists(sourcePath) }
        runCatching { Files.deleteIfExists(codePath) }
        null
    }

    private suspend fun download(offer: UpdateOffer) {
        pruneStaging()
        client.prepareGet(offer.downloadUrl).execute { response ->
            val expected = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(256 * 1024)
            val digest = MessageDigest.getInstance("SHA-256")
            val expectedDigest = normalizeSha256(offer.sha256)
            var received = 0L
            var lastPublish = 0L
            Files.createDirectories(updateDir)
            FileOutputStream(downloadPath.toFile()).use { out ->
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    received += read
                    val now = nowMs()
                    if (now - lastPublish >= 200L) {
                        lastPublish = now
                        attempts.update(
                            UpdateDownloadState.Downloading(
                                if (expected > 0) (received.toDouble() / expected).toFloat() else null, offer,
                            ),
                        )
                    }
                }
            }
            if (expected > 0 && received != expected) {
                runCatching { Files.deleteIfExists(downloadPath) }
                throw IllegalStateException("Truncated update download: expected $expected bytes, got $received")
            }
            val actualDigest = digest.digest().toHexString()
            if (expectedDigest == null) {
                // The releases-API fallback publishes no per-asset digest; length stays the only check.
                System.err.println("natune-update: feed published no sha256; verified length only ($received bytes)")
            } else if (expectedDigest != actualDigest) {
                runCatching { Files.deleteIfExists(downloadPath) }
                throw IllegalStateException(
                    "Update digest mismatch: expected sha256:$expectedDigest, got sha256:$actualDigest",
                )
            }
            val extension = offer.downloadUrl.substringAfterLast('.', "bin").substringBefore('?').lowercase()
            val target = stagedPath(extension)
            Files.move(downloadPath, target, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(sourcePath, offer.downloadUrl)
            Files.writeString(codePath, offer.versionCode.toString())
            attempts.update(UpdateDownloadState.ReadyToInstall(reused = false, offer = offer))
        }
    }

    private suspend fun pruneStaging() = withContext(Dispatchers.IO) {
        runCatching {
            Files.createDirectories(updateDir)
            listOf(downloadPath, sourcePath, codePath).forEach { Files.deleteIfExists(it) }
            Files.list(updateDir).use { stream ->
                stream.filter { it.fileName.toString().startsWith("natune-update.") }.forEach { Files.deleteIfExists(it) }
            }
        }
        Unit
    }

    /**
     * Platform-native install: launchable packages go to the OS handler (the msi/exe/dmg installer
     * then owns the swap); non-launchable archives reveal their folder so the user runs the install
     * themselves — the caller surfaces the matching instructions. Null when nothing is staged.
     */
    suspend fun install(offer: UpdateOffer): UpdateInstallOutcome? = withContext(Dispatchers.IO) {
        val staged = stagedFor(offer) ?: return@withContext null
        val extension = staged.fileName.toString().substringAfterLast('.').lowercase()
        if (extension in LAUNCHABLE_EXTENSIONS && Desktop.isDesktopSupported()) {
            runCatching { Desktop.getDesktop().open(staged.toFile()) }
                .onSuccess { return@withContext UpdateInstallOutcome.OPENED }
        }
        runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(updateDir.toFile())
        }.onSuccess { return@withContext UpdateInstallOutcome.REVEALED }
        runCatching { Desktop.getDesktop().browse(URI(offer.downloadUrl)) }
        UpdateInstallOutcome.REVEALED
    }
}
