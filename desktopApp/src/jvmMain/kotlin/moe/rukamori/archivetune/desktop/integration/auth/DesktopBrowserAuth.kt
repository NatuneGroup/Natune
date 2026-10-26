/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.desktop.integration.DesktopDeepLinks
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthPort
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthRequest
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthResult
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope

class DesktopBrowserUnavailable : IllegalStateException("Isolated desktop browser unavailable")

/**
 * A real installed Chromium-family browser, never the user's default profile.
 * Private mode keeps auth cookies/storage memory-only; each provider AND scope additionally has a
 * separate owner-locked user-data directory/process. This port returns OAuth callbacks, not cookies.
 * No sandbox-disabling flag, remote-debugging listener, subprocess output or callback persistence.
 */
class DesktopBrowserAuth internal constructor(
    private val links: DesktopDeepLinks,
    private val directory: Path,
    private val executable: Path,
    private val launch: (List<String>) -> Process,
) : BrowserAuthPort {
    constructor(
        links: DesktopDeepLinks,
        directory: Path = defaultBrowserDirectory(),
        executable: Path = findBrowser(),
    ) : this(links, directory, executable, { command ->
        ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    })
    private class Slot {
        val mutex = Mutex()
        val clearing = AtomicBoolean(false)
        val pending = AtomicReference<CompletableDeferred<BrowserAuthResult>?>(null)
    }
    private val slots = mutableMapOf<String, Slot>()
    private val endpoints = mutableSetOf<String>()
    private fun identity(provider: String, scope: CredentialScope) = credentialAccount(CredentialKey(provider, scope, "browser-profile"))
    private fun slot(id: String) = synchronized(slots) { slots.getOrPut(id) { Slot() } }

    override suspend fun authorize(request: BrowserAuthRequest): BrowserAuthResult {
        validateRequest(request)
        val id = identity(request.provider, request.scope)
        val slot = slot(id)
        check(slot.mutex.tryLock()) { "An authorization or profile reset is already active" }
        val result = CompletableDeferred<BrowserAuthResult>()
        slot.pending.set(result)
        var endpointOwned = false
        try {
            check(!slot.clearing.get())
            synchronized(endpoints) { check(endpoints.add(request.redirectUri)); endpointOwned = true }
            return withContext(Dispatchers.IO) {
                withProfileLock(id) { profile ->
                    var callback: AutoCloseable? = null
                    var process: Process? = null
                    try {
                        if (!result.isActive) result.await()
                        // Register first: a fast redirect cannot race browser startup.
                        callback = links.expectCallback(request.redirectUri, request.state) { raw ->
                            result.complete(BrowserAuthResult(raw, request.state))
                        }
                        check(executable.isAbsolute && Files.isRegularFile(executable) && Files.isExecutable(executable))
                        process = launch(listOf(
                            executable.toString(), "--user-data-dir=$profile", "--incognito", "--no-first-run",
                            "--no-default-browser-check", "--disable-background-mode", "--disable-sync",
                            "--app=${request.authorizationUrl}",
                        ))
                        process.onExit().whenComplete { _, _ -> result.completeExceptionally(DesktopBrowserUnavailable()) }
                        withTimeout(300_000) { result.await() }
                    } finally {
                        callback?.close()
                        withContext(NonCancellable + Dispatchers.IO) { process?.let(::stopOwnedProcess) }
                    }
                }
            }
        } finally {
            slot.pending.compareAndSet(result, null)
            result.cancel()
            if (endpointOwned) synchronized(endpoints) { endpoints.remove(request.redirectUri) }
            slot.mutex.unlock()
        }
    }

    override suspend fun clearProfile(provider: String, scope: CredentialScope) {
        val id = identity(provider, scope)
        val slot = slot(id)
        check(slot.clearing.compareAndSet(false, true))
        try {
            slot.pending.get()?.cancel()
            slot.mutex.withLock {
                withContext(Dispatchers.IO) {
                    withProfileLock(id) { profile ->
                        // No FOLLOW_LINKS. A browser-created symlink is unlinked, never traversed.
                        Files.walk(profile).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                    }
                }
            }
        } finally { slot.clearing.set(false) }
    }

    private suspend fun <T> withProfileLock(id: String, use: suspend (Path) -> T): T {
        privateDirectory(directory)
        val owner = directory.resolve(id)
        privateDirectory(owner)
        val lockPath = owner.resolve(".owner.lock")
        check(!Files.isSymbolicLink(lockPath))
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
            (channel.tryLock() ?: throw DesktopBrowserUnavailable()).use {
                val profile = owner.resolve("profile")
                privateDirectory(profile)
                return use(profile)
            }
        }
    }
}

internal fun privateDirectory(path: Path) {
    require(path.isAbsolute)
    check(!Files.isSymbolicLink(path))
    val posix = path.fileSystem.supportedFileAttributeViews().contains("posix")
    if (posix) {
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        check(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
    } else { Files.createDirectories(path) }
    check(Files.isDirectory(path, NOFOLLOW_LINKS))
}

internal fun validateRequest(request: BrowserAuthRequest) {
    require(request.state.length in 1..1024 && request.authorizationUrl.length <= 8192)
    require(request.authorizationUrl.none { it.code < 32 })
    val uri = try { URI(request.authorizationUrl) }
    catch (_: Exception) { throw IllegalArgumentException("Invalid desktop authorization request") }
    require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.rawFragment == null)
    require(uri.port == -1 || uri.port == 443)
    val parameters = uri.rawQuery.orEmpty().split('&').map {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, Charsets.UTF_8)
    }
    val states = parameters.filter { it.first == "state" }.map { it.second }
    val redirects = parameters.filter { it.first == "redirect_uri" }.map { it.second }
    require(states.size == 1 && MessageDigest.isEqual(
        states.single().toByteArray(Charsets.UTF_8), request.state.toByteArray(Charsets.UTF_8),
    ))
    require(redirects.size == 1 && redirects.single() == request.redirectUri)
}

internal fun stopOwnedProcess(process: Process) {
    val children = process.descendants().use { it.toList() }
    val exited = CompletableFuture.allOf(process.onExit(), *children.map { it.onExit() }.toTypedArray())
    process.destroy()
    children.forEach { it.destroy() }
    try { exited.get(2, TimeUnit.SECONDS) } catch (_: java.util.concurrent.TimeoutException) {
        process.destroyForcibly()
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
        exited.get(2, TimeUnit.SECONDS)
    }
}

private fun defaultBrowserDirectory(): Path = if (System.getProperty("os.name").startsWith("Windows")) {
    Path.of(System.getenv("LOCALAPPDATA") ?: throw DesktopBrowserUnavailable(), "ArchiveTune", "browser-auth")
} else { Path.of(System.getProperty("user.home"), ".archivetune", "browser-auth") }

private fun findBrowser(): Path = listOfNotNull(
    if (System.getProperty("os.name").startsWith("Windows")) System.getenv("PROGRAMFILES")?.let {
        Path.of(it, "Google", "Chrome", "Application", "chrome.exe")
    } else null,
    Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
    Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"),
    Path.of("/usr/bin/google-chrome"), Path.of("/usr/bin/chromium"), Path.of("/usr/bin/chromium-browser"),
).firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) } ?: throw DesktopBrowserUnavailable()
