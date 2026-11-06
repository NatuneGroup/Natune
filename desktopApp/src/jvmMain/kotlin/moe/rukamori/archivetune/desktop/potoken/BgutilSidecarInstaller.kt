package moe.rukamori.archivetune.desktop.potoken

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Resolves the pinned helper binary inside [root]: a cached file is reused only when its digest, size and header
 * still match the pinned release, otherwise it is downloaded again to a sibling `.download` file and moved into
 * place only after every check passed. A mismatch deletes the partial file and answers null, so playback falls back
 * to the untokened path instead of executing bytes that do not match the pin.
 *
 * [download] is injected so the verification and cache policy can be tested without the network.
 */
internal class BgutilSidecarInstaller(
    private val root: Path,
    private val download: suspend (BgutilSidecarSpec, Path) -> Unit,
    private val digest: (Path) -> String? = ::sha256Hex,
    private val log: (String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun resolve(spec: BgutilSidecarSpec): Path? =
        withContext(dispatcher) {
            mutex.withLock {
                val target = root.resolve(spec.binaryFileName)
                if (isVerified(target, spec)) return@withLock target

                // A file that failed the pin is never reused, and never left where a later launch could trust it.
                deleteQuietly(target)
                val staging = root.resolve("${spec.assetName}.download")
                deleteQuietly(staging)
                try {
                    Files.createDirectories(root)
                    download(spec, staging)
                    if (!isVerified(staging, spec)) {
                        log("PO token helper asset failed verification")
                        deleteQuietly(staging)
                        return@withLock null
                    }
                    moveIntoPlace(staging, target)
                    if (!markExecutable(target, spec)) {
                        log("PO token helper asset is not executable")
                        deleteQuietly(target)
                        return@withLock null
                    }
                    target
                } catch (cancelled: CancellationException) {
                    deleteQuietly(staging)
                    throw cancelled
                } catch (failure: Exception) {
                    log("PO token helper download failed: ${failure.javaClass.simpleName}")
                    deleteQuietly(staging)
                    null
                }
            }
        }

    /** The cached path when it still matches the pin, otherwise null. Never downloads. */
    suspend fun cached(spec: BgutilSidecarSpec): Path? =
        withContext(dispatcher) {
            mutex.withLock {
                val target = root.resolve(spec.binaryFileName)
                target.takeIf { isVerified(it, spec) }
            }
        }

    private fun isVerified(path: Path, spec: BgutilSidecarSpec): Boolean {
        if (!Files.isRegularFile(path)) return false
        if (spec.assetBytes > 0L && Files.size(path) != spec.assetBytes) return false
        if (Files.size(path) > BgutilSidecarRelease.MAX_ASSET_BYTES) return false
        if (digest(path) != spec.sha256) return false
        return isNativeExecutable(path)
    }

    private fun markExecutable(path: Path, spec: BgutilSidecarSpec): Boolean {
        if (spec.platform == BgutilHostPlatform.WINDOWS) return true
        if (Files.isExecutable(path)) return true
        return try {
            path.toFile().setExecutable(true, false) && Files.isExecutable(path)
        } catch (_: Exception) {
            false
        }
    }

    private fun moveIntoPlace(staging: Path, target: Path) {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: Exception) {
        }
    }
}

/**
 * Downloads one pinned asset from the official GitHub release. Only the URL derived from the compiled-in spec is
 * requested; redirects stay inside the release host chain, and the byte count is bounded before and during the read.
 */
internal class HttpBgutilSidecarDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {
    suspend fun download(spec: BgutilSidecarSpec, destination: Path): Unit =
        withContext(Dispatchers.IO) {
            val connection = java.net.URI.create(spec.downloadUrl).toURL().openConnection()
            val http = connection as? java.net.HttpURLConnection
                ?: throw IllegalStateException("PO token helper release URL is not HTTP")
            try {
                http.instanceFollowRedirects = true
                http.connectTimeout = connectTimeoutMs
                http.readTimeout = readTimeoutMs
                http.requestMethod = "GET"
                val declared = http.getHeaderFieldLong("Content-Length", -1L)
                if (declared > BgutilSidecarRelease.MAX_ASSET_BYTES) {
                    throw IllegalStateException("PO token helper asset exceeds the size limit")
                }
                val code = http.responseCode
                if (code !in 200..299) throw IllegalStateException("PO token helper download returned HTTP $code")
                Files.newOutputStream(destination).use { output ->
                    http.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > BgutilSidecarRelease.MAX_ASSET_BYTES) {
                                throw IllegalStateException("PO token helper asset exceeds the size limit")
                            }
                            output.write(buffer, 0, read)
                        }
                        if (total == 0L) throw IllegalStateException("PO token helper download was empty")
                    }
                }
            } finally {
                http.disconnect()
            }
        }
}
