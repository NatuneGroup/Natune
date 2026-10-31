package moe.rukamori.archivetune.shared.domain.settings

/** The Canvas providers whose availability the settings page reports. */
enum class CanvasHealthProvider { BETTER_LYRICS, APPLE_MUSIC, TIDAL, SPOTIFY }

/**
 * Availability as the settings page shows it. [CHECKING] means every settings and network gate passes, so only a
 * host probe can decide between [AVAILABLE] and [UNAVAILABLE].
 */
enum class CanvasHealthState {
    NOT_CHECKED,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
    NOT_CONNECTED,
    NOT_SELECTED,
    DISABLED,
    OFFLINE,
    WIFI_REQUIRED,
    LOW_DATA_MODE,
}

/**
 * Host-measured network state. Callers pass null until the host has measured it, which reads as not checked.
 * [cellular] is known only on hosts that can tell a cellular link from Wi-Fi; the others leave it false.
 */
data class CanvasConnectivity(
    val online: Boolean = false,
    val wifi: Boolean = false,
    val metered: Boolean = true,
    val cellular: Boolean = false,
)

/** Host probe progress for one provider. The shared policy never probes; it only reads what the host reports. */
enum class CanvasProbeState { NOT_MEASURED, CHECKING, AVAILABLE, UNAVAILABLE }

/** The host's probe state for every provider. Everything starts unmeasured. */
data class CanvasProviderProbes(
    val betterLyrics: CanvasProbeState = CanvasProbeState.NOT_MEASURED,
    val appleMusic: CanvasProbeState = CanvasProbeState.NOT_MEASURED,
    val tidal: CanvasProbeState = CanvasProbeState.NOT_MEASURED,
    val spotify: CanvasProbeState = CanvasProbeState.NOT_MEASURED,
) {
    fun of(provider: CanvasHealthProvider): CanvasProbeState = when (provider) {
        CanvasHealthProvider.BETTER_LYRICS -> betterLyrics
        CanvasHealthProvider.APPLE_MUSIC -> appleMusic
        CanvasHealthProvider.TIDAL -> tidal
        CanvasHealthProvider.SPOTIFY -> spotify
    }
}

/**
 * The stored source choice accepts a provider when it is that provider or the ALL choice. A missing, unknown or
 * legacy BOTH value reads as ALL, as the baseline `CanvasSource.fromPreference` did.
 */
fun PreferenceSnapshot.canvasSourceAccepts(provider: CanvasHealthProvider): Boolean {
    val selected = CanvasHealthProvider.entries.firstOrNull { it.name == this[CanvasSourceKey] }
    return selected == null || selected == provider
}

/** The Spotify catalog account counts as connected while its sp_dc cookie is stored, as the baseline checked. */
fun PreferenceSnapshot.canvasSpotifyConnected(): Boolean = !this[SpotifySpDcKey].isNullOrBlank()

/**
 * The settings and network gate for one provider, before any probe. Only [CanvasHealthState.CHECKING] means the
 * provider needs a host probe. The order matches the baseline, so a closed source choice wins over a disabled canvas.
 */
fun PreferenceSnapshot.canvasHealthGate(
    provider: CanvasHealthProvider,
    connectivity: CanvasConnectivity?,
): CanvasHealthState = when {
    connectivity == null -> CanvasHealthState.NOT_CHECKED
    !canvasSourceAccepts(provider) -> CanvasHealthState.NOT_SELECTED
    !this[ArchiveTuneCanvasKey, false] -> CanvasHealthState.DISABLED
    provider == CanvasHealthProvider.SPOTIFY && !canvasSpotifyConnected() -> CanvasHealthState.NOT_CONNECTED
    !connectivity.online -> CanvasHealthState.OFFLINE
    this[CanvasWifiOnlyKey, false] && !connectivity.wifi -> CanvasHealthState.WIFI_REQUIRED
    !animatedArtworkAllowed(connectivity) -> CanvasHealthState.WIFI_REQUIRED
    lowDataMode() && connectivity.metered -> CanvasHealthState.LOW_DATA_MODE
    else -> CanvasHealthState.CHECKING
}

/** The providers whose gate is open. The host probes exactly these, so a closed gate never triggers a request. */
fun PreferenceSnapshot.canvasProvidersToProbe(connectivity: CanvasConnectivity?): Set<CanvasHealthProvider> =
    CanvasHealthProvider.entries.filterTo(mutableSetOf()) { canvasHealthGate(it, connectivity) == CanvasHealthState.CHECKING }

/**
 * The state shown for one provider. A closed gate is shown as it is. An open gate shows the host's probe state, and
 * a probe that has not reported yet is pending, which reads as CHECKING.
 */
fun canvasHealthOf(gate: CanvasHealthState, probe: CanvasProbeState): CanvasHealthState =
    if (gate != CanvasHealthState.CHECKING) {
        gate
    } else {
        when (probe) {
            CanvasProbeState.NOT_MEASURED, CanvasProbeState.CHECKING -> CanvasHealthState.CHECKING
            CanvasProbeState.AVAILABLE -> CanvasHealthState.AVAILABLE
            CanvasProbeState.UNAVAILABLE -> CanvasHealthState.UNAVAILABLE
        }
    }

/** The state shown for every provider, from the current settings, connectivity and the host's probe results. */
fun PreferenceSnapshot.canvasHealthStates(
    connectivity: CanvasConnectivity?,
    probes: CanvasProviderProbes,
): Map<CanvasHealthProvider, CanvasHealthState> = CanvasHealthProvider.entries.associateWith { provider ->
    canvasHealthOf(canvasHealthGate(provider, connectivity), probes.of(provider))
}

/**
 * Whether the network allows Canvas at all: enabled, online, and inside the wifi-only, low-data and animated-artwork
 * mobile-data rules. A refresh is offered only when this holds and no provider is still being checked.
 */
fun PreferenceSnapshot.canvasNetworkAllowed(connectivity: CanvasConnectivity?): Boolean {
    if (connectivity == null || !this[ArchiveTuneCanvasKey, false]) return false
    return connectivity.online &&
        (!this[CanvasWifiOnlyKey, false] || connectivity.wifi) &&
        (!lowDataMode() || !connectivity.metered) &&
        animatedArtworkAllowed(connectivity)
}
