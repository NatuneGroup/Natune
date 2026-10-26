/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration.auth

import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import moe.rukamori.archivetune.desktop.integration.DesktopDeepLinks
import moe.rukamori.archivetune.shared.domain.platform.*
import org.junit.Assume

/** Process doubles below characterize ownership only, never live browser/cookie/OAuth parity. */
class DesktopBrowserAuthTest {
    private val links = DesktopDeepLinks(setOf("archivetune"), setOf("open"))
    private fun request(provider: String = "spotify", scope: CredentialScope = CredentialScope.SPOTIFY_CATALOG,
        suffix: String = scope.name): BrowserAuthRequest {
        val redirect = "archivetune://oauth/$provider/$suffix"
        return BrowserAuthRequest(provider, scope, "https://example.invalid/authorize?state=expected&redirect_uri=" +
            URLEncoder.encode(redirect, Charsets.UTF_8), redirect, "expected")
    }
    @Test fun matchingCallbackIsSingleUseAndAlwaysStopsOnlyItsOwnedProcess() = runBlocking {
        fixture { port, started ->
            val req = request()
            val pending = async { port.authorize(req) }
            val (process, command) = started.receive()
            assertTrue("--incognito" in command)
            assertFalse(command.any { it == "--no-sandbox" || it.startsWith("--remote-debugging") })
            assertFalse(links.accept("${req.redirectUri}?state=wrong"))
            assertTrue(links.accept("${req.redirectUri}?state=expected&code=one"))
            assertEquals("${req.redirectUri}?state=expected&code=one", pending.await().callbackUri)
            assertFalse(process.isAlive)
            assertFalse(links.accept("${req.redirectUri}?state=expected&code=replay"))
        }
    }
    @Test fun cancellationAndClearKeepCatalogStreamingPoolAndProvidersSeparate() = runBlocking {
        fixture { port, started ->
            val catalog = async { port.authorize(request()) }
            val (_, catalogCommand) = started.receive()
            val streaming = async { port.authorize(request(scope = CredentialScope.SPOTIFY_STREAMING)) }
            val (streamProcess, streamCommand) = started.receive()
            val pool = async { port.authorize(request(scope = CredentialScope.PLAYBACK_POOL)) }
            val (poolProcess, poolCommand) = started.receive()
            val other = async { port.authorize(request(provider = "other")) }
            val (otherProcess, otherCommand) = started.receive()
            val profiles = listOf(catalogCommand, streamCommand, poolCommand, otherCommand).map {
                Path.of(it.single { argument -> argument.startsWith("--user-data-dir=") }.substringAfter('='))
            }
            assertEquals(4, profiles.toSet().size)
            profiles.forEach { Files.writeString(it.resolve("owned-cache"), "nonsecret-component-marker") }
            port.clearProfile("spotify", CredentialScope.SPOTIFY_CATALOG)
            assertFailsWith<CancellationException> { catalog.await() }
            assertFalse(Files.exists(profiles[0]))
            assertTrue(streamProcess.isAlive && poolProcess.isAlive && otherProcess.isAlive)
            profiles.drop(1).forEach { assertTrue(Files.exists(it.resolve("owned-cache"))) }
            streaming.cancelAndJoin(); pool.cancelAndJoin(); other.cancelAndJoin()
            assertFalse(streamProcess.isAlive || poolProcess.isAlive || otherProcess.isAlive)
        }
    }
    @Test fun invalidRequestNeverLaunchesBrowser() = runBlocking {
        fixture { port, started ->
            for (bad in listOf(
                request().copy(authorizationUrl = "http://example.invalid"),
                request().copy(state = "wrong"),
                request().copy(authorizationUrl = request().authorizationUrl + "&state=expected"),
                request().copy(authorizationUrl = request().authorizationUrl + "#fragment"),
            )) assertFailsWith<IllegalArgumentException> { port.authorize(bad) }
            assertTrue(started.tryReceive().isFailure)
        }
    }
    @Test fun endpointConflictDoesNotReplaceOriginalAttempt() = runBlocking {
        fixture { port, started ->
            val req = request()
            val first = async { port.authorize(req) }
            started.receive()
            assertFailsWith<IllegalStateException> {
                port.authorize(req.copy(scope = CredentialScope.SPOTIFY_STREAMING))
            }
            assertTrue(links.accept("${req.redirectUri}?state=expected"))
            first.await()
            assertTrue(started.tryReceive().isFailure)
        }
    }
    @Test fun browserExitWithoutCallbackIsFailureNotSyntheticSuccess() = runBlocking {
        fixture { port, started ->
            // supervisor isolates the deliberately failed child.
            supervisorScope {
                val pending = async { port.authorize(request()) }
                started.receive().first.destroy()
                assertFailsWith<DesktopBrowserUnavailable> { pending.await() }
            }
        }
    }
    @Test fun realIsolatedBrowserCanStartWhenExplicitlyRequested() = runBlocking {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("archivetune.desktop.browser.tests"))
        val root = Files.createTempDirectory("archivetune-live-browser")
        try {
            val port = DesktopBrowserAuth(links, root)
            val req = request()
            val stream = request(scope = CredentialScope.SPOTIFY_STREAMING)
            withTimeout(10_000) { supervisorScope {
                val pending = async { port.authorize(req) }
                val streaming = async { port.authorize(stream) }
                try {
                    val profiles = listOf(req, stream).map { request ->
                        root.resolve(credentialAccount(CredentialKey(request.provider, request.scope, "browser-profile"))).resolve("profile")
                    }
                    withTimeout(8000) {
                        while (profiles.any { !Files.isRegularFile(it.resolve("Local State")) }) {
                            if (pending.isCompleted) pending.await()
                            if (streaming.isCompleted) streaming.await()
                            delay(25)
                        }
                    }
                    assertNotEquals(profiles[0], profiles[1])
                    port.clearProfile(req.provider, req.scope)
                    assertFailsWith<CancellationException> { pending.await() }
                    assertFalse(Files.exists(profiles[0]))
                    assertTrue(streaming.isActive && Files.isRegularFile(profiles[1].resolve("Local State")))
                    assertTrue(links.accept("${stream.redirectUri}?state=expected&code=local-component-probe"))
                    streaming.await()
                } finally { pending.cancelAndJoin(); streaming.cancelAndJoin() }
            } }
            port.clearProfile(req.provider, req.scope)
            port.clearProfile(stream.provider, stream.scope)
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private suspend fun fixture(test: suspend CoroutineScope.(DesktopBrowserAuth, Channel<Pair<ComponentProcess, List<String>>>) -> Unit) =
        coroutineScope {
            withTimeout(10_000) {
                val root = Files.createTempDirectory("archivetune-browser-component")
                val started = Channel<Pair<ComponentProcess, List<String>>>(Channel.UNLIMITED)
                val processes = mutableListOf<ComponentProcess>()
                try {
                    val java = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
                    val port = DesktopBrowserAuth(links, root, Path.of(System.getProperty("java.home"), "bin", java)) { command ->
                        ComponentProcess().also { synchronized(processes) { processes += it }; started.trySend(it to command) }
                    }
                    test(port, started)
                } finally {
                    synchronized(processes) { processes.forEach { it.destroy() } }
                    started.close()
                    Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                }
            }
        }
    private class ComponentProcess : Process() {
        private val exit = CompletableFuture<Process>()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int { exit.get(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { exit.get(timeout, unit); return true }
        override fun exitValue(): Int { check(exit.isDone); return 0 }
        override fun destroy() { exit.complete(this) }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive() = !exit.isDone
        override fun onExit(): CompletableFuture<Process> = exit
        override fun descendants(): Stream<ProcessHandle> = Stream.empty()
    }
}
