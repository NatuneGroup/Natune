package moe.rukamori.archivetune.desktop.potoken

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BgutilSidecarManagerTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { runCatching { it.toFile().deleteRecursively() } }
        roots.clear()
    }

    @Test
    fun aDisabledHelperNeverStartsAProcess() = runTest {
        val launcher = FakeLauncher()
        val manager = newManager(backgroundScope, enabled = false, launcher = launcher)

        assertNull(manager.ensureRunning())
        assertEquals(BgutilSidecarStatus.Disabled, manager.status.value)
        assertTrue(launcher.commands.isEmpty())
    }

    @Test
    fun anUnsupportedHostReportsFailureWithoutDownloading() = runTest {
        val launcher = FakeLauncher()
        val manager = newManager(backgroundScope, supported = false, launcher = launcher)

        assertNull(manager.ensureRunning())
        assertEquals(BgutilSidecarStatus.Failed(BgutilSidecarManager.UNSUPPORTED_REASON), manager.status.value)
        assertTrue(launcher.commands.isEmpty())
    }

    @Test
    fun startsOnLoopbackAndReportsTheReadyPort() = runTest {
        val launcher = FakeLauncher()
        val manager = newManager(backgroundScope, launcher = launcher)

        assertEquals("http://127.0.0.1:4416", manager.ensureRunning())

        val running = manager.status.value as BgutilSidecarStatus.Running
        assertEquals(4416, running.port)
        val command = launcher.commands.single()
        assertEquals(
            listOf(
                root().resolve(TEST_SPEC.binaryFileName).toString(),
                "server",
                "--host",
                "127.0.0.1",
                "--port",
                "4416",
            ),
            command,
        )
        assertTrue(launcher.processes.single().isAlive)

        manager.close()
        assertTrue(launcher.processes.single().destroyed)
    }

    @Test
    fun aFailedHealthCheckEntersBackoffAndDoesNotRespawnEveryCall() = runTest {
        val clock = FakeClock()
        val launcher = FakeLauncher()
        val manager = newManager(backgroundScope, transport = FakeTransport(healthy = false), launcher = launcher, clock = clock)

        assertNull(manager.ensureRunning(waitMs = 5_000))
        assertTrue(manager.status.value is BgutilSidecarStatus.Failed)
        assertEquals(1, launcher.commands.size)
        assertTrue(launcher.processes.single().destroyed)

        assertNull(manager.ensureRunning())
        assertEquals(1, launcher.commands.size)

        clock.advance(BgutilSidecarManager.BASE_BACKOFF_MS)
        assertNull(manager.ensureRunning(waitMs = 5_000))
        assertEquals(2, launcher.commands.size)
    }

    @Test
    fun supervisionRestartsACrashedProcessAfterBackoff() = runTest {
        val clock = FakeClock()
        val launcher = FakeLauncher()
        val manager =
            newManager(
                backgroundScope,
                launcher = launcher,
                clock = clock,
                supervise = true,
                pause = { delay(it).also { clock.advance(it) } },
            )

        assertEquals("http://127.0.0.1:4416", manager.ensureRunning())
        assertEquals(1, launcher.processes.size)

        launcher.processes.single().alive = false
        advanceTimeBy(BgutilSidecarManager.SUPERVISION_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(BgutilSidecarStatus.Starting, manager.status.value)

        advanceTimeBy(BgutilSidecarManager.BASE_BACKOFF_MS + 1)
        runCurrent()
        assertEquals(2, launcher.processes.size)
        assertTrue(manager.status.value is BgutilSidecarStatus.Running)
    }

    private fun newManager(
        scope: CoroutineScope,
        supported: Boolean = true,
        enabled: Boolean = true,
        transport: FakeTransport = FakeTransport(healthy = true),
        launcher: FakeLauncher = FakeLauncher(),
        clock: FakeClock = FakeClock(),
        supervise: Boolean = false,
        pause: suspend (Long) -> Unit = { clock.advance(it) },
    ): BgutilSidecarManager {
        val directory = root()
        val installer =
            BgutilSidecarInstaller(
                root = directory,
                download = { _, target -> Files.write(target, TEST_PAYLOAD) },
                dispatcher = Dispatchers.Unconfined,
            )
        return BgutilSidecarManager(
            scope = scope,
            installRoot = directory,
            spec = if (supported) TEST_SPEC else null,
            enabled = { enabled },
            installer = installer,
            transport = transport,
            launcher = launcher,
            nextPort = { 4416 },
            nowMs = { clock.now },
            pause = pause,
            supervise = supervise,
        )
    }

    private fun root(): Path = currentRoot

    private val currentRoot: Path
        get() {
            if (roots.isEmpty()) roots += Files.createTempDirectory("potoken-manager-test")
            return roots.last()
        }

    private class FakeProcess : BgutilProcessHandle {
        @Volatile var alive = true
        @Volatile var destroyed = false

        override val isAlive: Boolean get() = alive

        override fun destroy() {
            destroyed = true
            alive = false
        }
    }

    private class FakeLauncher : BgutilProcessLauncher {
        val commands = mutableListOf<List<String>>()
        val processes = mutableListOf<FakeProcess>()

        override fun launch(command: List<String>, workingDirectory: Path?, logFile: Path?): BgutilProcessHandle {
            commands += command
            return FakeProcess().also { processes += it }
        }
    }

    private class FakeTransport(private val healthy: Boolean) : BgutilSidecarTransport {
        override suspend fun ping(baseUrl: String): Boolean = healthy

        override suspend fun fetchPot(baseUrl: String, contentBinding: String): String = "token-$contentBinding"
    }

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(millis: Long) {
            now += millis
        }
    }

    private companion object {
        val TEST_PAYLOAD: ByteArray = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00, 0x10)
        val TEST_SPEC =
            BgutilSidecarSpec(
                platform = BgutilHostPlatform.LINUX,
                arch = BgutilHostArch.X64,
                assetName = "bgutil-pot-test",
                sha256 = MessageDigest.getInstance("SHA-256").digest(TEST_PAYLOAD).joinToString("") { byte ->
                    (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
                },
                binaryFileName = "bgutil-pot-test",
                assetBytes = TEST_PAYLOAD.size.toLong(),
            )
    }
}
