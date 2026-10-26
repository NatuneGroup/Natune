package moe.rukamori.archivetune.shared.data.platform.ios

import moe.rukamori.archivetune.shared.data.provider.TidalClientCredentials
import platform.Foundation.NSBundle

/**
 * Provider OAuth clients of the iOS build, read from Info.plist keys written by the
 * ARCHIVETUNE_TIDAL_* Xcode build settings (same convention as [iosTelegramApiCredentials]).
 * An unset build setting reaches the plist as its literal `$(…)` reference, which is not a
 * usable credential — the corresponding flow then stays not configured.
 */
fun iosProviderValue(name: String): String {
    val value = (NSBundle.mainBundle.objectForInfoDictionaryKey(name) as? String)?.trim().orEmpty()
    return if (value.isBlank() || value.startsWith("$(")) "" else value
}

private fun iosTidalClient(idKey: String, secretKey: String): TidalClientCredentials? {
    val id = iosProviderValue(idKey)
    val secret = iosProviderValue(secretKey)
    return if (id.isBlank() || secret.isBlank()) null else TidalClientCredentials(id, secret)
}

/** The TIDAL device-authorization client; null when this build does not configure one. */
fun iosTidalDeviceClient(): TidalClientCredentials? =
    iosTidalClient("ArchiveTuneTidalDeviceClientId", "ArchiveTuneTidalDeviceClientSecret")
        ?: iosTidalClient("ArchiveTuneTidalClientId", "ArchiveTuneTidalClientSecret")

/** The TIDAL PKCE web-login client; null keeps the embedded web sign-in not configured. */
fun iosTidalPkceClient(): TidalClientCredentials? =
    iosTidalClient("ArchiveTuneTidalPkceClientId", "ArchiveTuneTidalPkceClientSecret")
