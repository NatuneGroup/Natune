package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLevel
import moe.rukamori.archivetune.shared.ui.glass.OpticalCapabilities

/**
 * What the window actually renders with. Skiko picks a backend per OS and silently walks a fallback queue — OpenGL, then
 * software — when the GPU driver, a virtual machine or its own GPU blacklist refuses the first choice. A packaged GUI app
 * has no console, so this records the settled backend in the persistent diagnostic log and feeds the glass policy: blur is
 * per-frame work and is only offered while a GPU backend is drawing.
 */
internal object DesktopRendererDiagnostics {
    // The only Skiko values that rasterise on the CPU; every other backend draws blur on the GPU.
    private val SOFTWARE_APIS = setOf("SOFTWARE_FAST", "SOFTWARE_COMPAT")
    private const val REQUEST_PROPERTY = "skiko.renderApi"
    private const val ESCAPE_HATCH = "NATUNE_RENDER_API"
    private const val PROBE_ATTEMPTS = 20
    private const val PROBE_INTERVAL_MS = 150L

    private val active = MutableStateFlow<String?>(null)
    private val mutableBlurSupported = MutableStateFlow(true)
    private val probing = AtomicBoolean()
    private val reported = AtomicBoolean()

    /** Whether the running renderer can afford the glass blur; a late fallback flips it and recomposes the root. */
    val blurSupported: StateFlow<Boolean> = mutableBlurSupported.asStateFlow()

    /** The capability value the non-composable readers (settings rows) see at their next composition. */
    val capabilities: OpticalCapabilities
        get() = OpticalCapabilities(blur = mutableBlurSupported.value, refraction = false)

    /**
     * Runs before the first window. `NATUNE_RENDER_API` is a support escape hatch mapped onto Skiko's own switch, never a
     * default: it is only applied when nothing else asked for an API, so a user-configured value still wins.
     */
    fun prepare() {
        val override = System.getenv(ESCAPE_HATCH)?.trim()?.takeIf(String::isNotEmpty)
        if (override != null && System.getProperty(REQUEST_PROPERTY).isNullOrBlank()) {
            System.setProperty(REQUEST_PROPERTY, override)
            DesktopDiagnosticLog.host("renderer: $ESCAPE_HATCH requested $override", DiagnosticLevel.INFO)
        }
        mutableBlurSupported.value = blurSupportedFor(resolveRequestedApi())
        DesktopDiagnosticLog.host(
            "renderer: requested=${requestedApi()} os=${System.getProperty("os.name").orEmpty()} " +
                "arch=${System.getProperty("os.arch").orEmpty()} blur=${if (mutableBlurSupported.value) "on" else "off"}",
            DiagnosticLevel.INFO,
        )
    }

    /** The requested API as the log and the diagnostic report name it: `auto` when Skiko is free to choose. */
    fun requestedApi(): String = resolveRequestedApi() ?: "auto"

    /** One line for Settings/crash device info: the settled backend next to the request and the blur decision. */
    fun describe(): String =
        "requested=${requestedApi()} active=${active.value ?: "unknown"} blur=${if (mutableBlurSupported.value) "on" else "off"}"

    /**
     * Finds the settled backend on the live window and logs it once. Compose creates its Skiko layer with the window, so
     * the probe starts immediately and retries briefly for a window that is still being shown. Runs from a composition
     * effect, which keeps the AWT component walk on the event dispatch thread.
     */
    suspend fun observe(window: Window) {
        if (!probing.compareAndSet(false, true)) return
        repeat(PROBE_ATTEMPTS) {
            // UNKNOWN is Skiko's pre-renderer placeholder, not a backend: keep waiting for the settled value.
            val api = activeRenderApi(window)?.takeIf { !it.equals("UNKNOWN", ignoreCase = true) }
            if (api != null) {
                active.value = api
                mutableBlurSupported.value = blurSupportedFor(api)
                if (reported.compareAndSet(false, true)) {
                    DesktopDiagnosticLog.host("renderer: active=$api ${describe()}", DiagnosticLevel.INFO)
                }
                return
            }
            delay(PROBE_INTERVAL_MS)
        }
        if (reported.compareAndSet(false, true)) {
            DesktopDiagnosticLog.host("renderer: the window did not report its active API; ${describe()}", DiagnosticLevel.INFO)
        }
    }

    /**
     * Skiko reports software rasterisation through the same enum the GPU backends use, so the decision is a set membership
     * test. An unknown backend keeps blur: it is the pre-probe default and a GPU backend is the normal case.
     */
    internal fun blurSupportedFor(api: String?): Boolean = api == null || api.uppercase() !in SOFTWARE_APIS

    /** The API requested by the host, or the one Skiko was allowed to choose. */
    internal fun resolveRequestedApi(): String? =
        System.getProperty(REQUEST_PROPERTY)?.trim()?.takeIf(String::isNotEmpty)

    /**
     * Compose's desktop layer components expose the backend Skiko settled on as `renderApi` (SkiaLayer's own property).
     * The lookup is reflective on purpose: it is Skiko's API, the host must start without it, and a future Compose that
     * moves the property only costs one unknown line in the log.
     */
    private fun activeRenderApi(window: Window): String? {
        val pending = ArrayDeque<Component>()
        window.components?.forEach { pending.addLast(it) }
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            renderApiOf(component)?.let { return it }
            if (component is Container) component.components?.forEach { pending.addLast(it) }
        }
        return null
    }

    private fun renderApiOf(component: Component): String? = try {
        component.javaClass.methods
            .firstOrNull { it.name == "getRenderApi" && it.parameterCount == 0 }
            ?.invoke(component)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}

/** The root's optical capabilities as composition state, so a detected software fallback recomposes into crisp surfaces. */
@Composable
internal fun rememberDesktopOpticalCapabilities(): OpticalCapabilities {
    val blur by DesktopRendererDiagnostics.blurSupported.collectAsState()
    return OpticalCapabilities(blur = blur, refraction = false)
}
