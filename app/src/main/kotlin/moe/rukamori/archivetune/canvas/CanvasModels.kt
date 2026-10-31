/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.canvas

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import moe.rukamori.archivetune.constants.*

// The shared :shared-domain key has this same on-disk name, and the settings store maps keys by name.
private val DisableAnimatedArtworkOnMobileDataPreference = booleanPreferencesKey("disableAnimatedArtworkOnMobileData")

internal fun canvasConfigurationFrom(preferences: Preferences): CanvasConfiguration =
    CanvasConfiguration(
        enabled = preferences[ArchiveTuneCanvasKey] ?: false,
        source = CanvasSource.fromPreference(preferences[CanvasSourceKey]),
        wifiOnly = preferences[CanvasWifiOnlyKey] ?: false,
        cacheLimitMb = (preferences[MaxCanvasCacheSizeKey] ?: 256).coerceAtLeast(-1),
        lowDataMode = preferences[LowDataModeKey] ?: true,
        disableAnimatedArtworkOnMobileData = preferences[DisableAnimatedArtworkOnMobileDataPreference] ?: false,
    )

@Immutable
data class CanvasConfiguration(
    val enabled: Boolean = false,
    val source: CanvasSource = CanvasSource.ALL,
    val wifiOnly: Boolean = false,
    val cacheLimitMb: Int = 256,
    val lowDataMode: Boolean = false,
    val disableAnimatedArtworkOnMobileData: Boolean = false,
)

@Immutable
data class CanvasConnectivity(
    val online: Boolean = false,
    val wifi: Boolean = false,
    val metered: Boolean = true,
    val cellular: Boolean = false,
)

@Immutable
data class CanvasPolicy(
    val configuration: CanvasConfiguration = CanvasConfiguration(),
    val connectivity: CanvasConnectivity = CanvasConnectivity(),
    val ready: Boolean = false,
    val configurationError: Boolean = false,
) {
    val networkAllowed: Boolean
        get() = ready && configuration.enabled && connectivity.online &&
            (!configuration.wifiOnly || connectivity.wifi) &&
            (!configuration.lowDataMode || !connectivity.metered) &&
            (!configuration.disableAnimatedArtworkOnMobileData || !connectivity.cellular)
}

@Immutable
data class CanvasVideo(
    val source: CanvasSource,
    val static: String?,
    val animated: String?,
    val videoUrl: String?,
    val animatedVertical: String?,
    val videoUrlVertical: String?,
)

enum class CanvasHealth {
    NOT_CHECKED,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
    NOT_SELECTED,
    NOT_CONNECTED,
    DISABLED,
    OFFLINE,
    WIFI_REQUIRED,
    LOW_DATA_MODE,
}

@Immutable
data class CanvasHealthStatus(
    val betterLyrics: CanvasHealth = CanvasHealth.NOT_CHECKED,
    val appleMusic: CanvasHealth = CanvasHealth.NOT_CHECKED,
    val tidal: CanvasHealth = CanvasHealth.NOT_CHECKED,
    val spotify: CanvasHealth = CanvasHealth.NOT_CHECKED,
) {
    val checking: Boolean
        get() = betterLyrics == CanvasHealth.CHECKING || appleMusic == CanvasHealth.CHECKING ||
            tidal == CanvasHealth.CHECKING || spotify == CanvasHealth.CHECKING
}
