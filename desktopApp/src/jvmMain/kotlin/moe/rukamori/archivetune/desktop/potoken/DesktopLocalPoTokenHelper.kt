package moe.rukamori.archivetune.desktop.potoken

import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.shared.data.potoken.MintedPoTokens
import moe.rukamori.archivetune.shared.ui.settings.PoTokenHelperState
import moe.rukamori.archivetune.shared.ui.settings.PoTokenHelperStatus

/**
 * The desktop-local PO token source behind [moe.rukamori.archivetune.desktop.potoken.DesktopPoTokenOwner]. The
 * interface keeps the owner testable: a fake can answer tokens, answer null (the clean fallback) or be disabled,
 * without starting a process or opening a socket.
 */
internal interface LocalPoTokenHelper {
    /** True when this host has a pinned helper binary; false makes the capability advertise nothing. */
    val availableOnHost: Boolean

    /**
     * The player token bound to [videoId] and the session token bound to [sessionId], or null when the helper is
     * unavailable, disabled or did not answer in time.
     */
    suspend fun mint(videoId: String, sessionId: String): MintedPoTokens?

    /** Drops the token cache without stopping the process; called when an attestation is rejected. */
    suspend fun clearTokens()

    /** Stops a running helper after the user disables the switch; subsequent mints answer null. */
    suspend fun disable()

    suspend fun status(): PoTokenHelperStatus

    suspend fun close()
}

/** The real helper: one managed process, one bounded loopback client, no bundled binary and no build secret. */
internal class BgutilLocalPoTokenHelper(
    private val manager: BgutilSidecarManager,
    private val client: BgutilPotTokenClient,
    private val log: (String) -> Unit = {},
) : LocalPoTokenHelper {
    override val availableOnHost: Boolean get() = manager.supported

    override suspend fun mint(videoId: String, sessionId: String): MintedPoTokens? {
        if (videoId.isBlank() || sessionId.isBlank()) return null
        val baseUrl = manager.ensureRunning() ?: return null
        val tokens =
            try {
                client.mint(baseUrl, videoId, sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("PO token helper mint failed: ${failure.javaClass.simpleName}")
                null
            }
        if (tokens == null) log("PO token helper returned no token pair")
        return tokens
    }

    override suspend fun clearTokens() {
        client.clear()
    }

    override suspend fun disable() {
        client.clear()
        manager.disable()
    }

    override suspend fun status(): PoTokenHelperStatus {
        if (!availableOnHost) return PoTokenHelperStatus(PoTokenHelperState.UNSUPPORTED)
        return when (val current = manager.status.value) {
            BgutilSidecarStatus.Disabled -> PoTokenHelperStatus(PoTokenHelperState.DISABLED)
            BgutilSidecarStatus.Stopped -> PoTokenHelperStatus(PoTokenHelperState.IDLE)
            BgutilSidecarStatus.Starting -> PoTokenHelperStatus(PoTokenHelperState.STARTING)
            is BgutilSidecarStatus.Running ->
                PoTokenHelperStatus(
                    state = PoTokenHelperState.READY,
                    detail = current.baseUrl.removePrefix("http://"),
                )
            is BgutilSidecarStatus.Failed -> PoTokenHelperStatus(PoTokenHelperState.FAILED, current.reason)
        }
    }

    override suspend fun close() {
        client.clear()
        manager.close()
    }
}

/** Builds the real helper for one session graph; the process lives exactly as long as that graph. */
internal fun desktopLocalPoTokenHelper(
    scope: CoroutineScope,
    instanceDirectory: Path,
    enabled: () -> Boolean,
    log: (String) -> Unit,
): LocalPoTokenHelper {
    val root = instanceDirectory.resolve(HELPER_DIRECTORY)
    val spec = BgutilSidecarRelease.forHost()
    val transport = HttpBgutilSidecarTransport()
    val downloader = HttpBgutilSidecarDownloader()
    val installer =
        BgutilSidecarInstaller(
            root = root,
            download = downloader::download,
            log = log,
        )
    val manager =
        BgutilSidecarManager(
            scope = scope,
            installRoot = root,
            spec = spec,
            enabled = enabled,
            installer = installer,
            transport = transport,
            launcher = ProcessBuilderBgutilLauncher(),
            log = log,
        )
    return BgutilLocalPoTokenHelper(manager, BgutilPotTokenClient(transport), log)
}

private const val HELPER_DIRECTORY = "potoken-provider"
