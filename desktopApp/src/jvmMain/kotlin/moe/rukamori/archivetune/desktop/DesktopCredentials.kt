package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.shared.data.provider.TidalClientCredentials

/**
 * Provider credentials of the desktop host. Each value is read from an environment variable of the same name, then a
 * system property of the same name, then the value injected at build time. A blank answer means the feature is not
 * configured; no value is ever logged or written to settings.
 */
internal object DesktopCredentials {
    fun value(name: String): String {
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        System.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return built(name)
    }

    /** The TIDAL public web token for catalog reads over the HiFi instances, or null when this build has none. */
    val tidalWebToken: String? get() = value("ARCHIVETUNE_TIDAL_WEB_TOKEN").ifBlank { null }

    /**
     * The TIDAL device-flow client. A dedicated device client wins; otherwise the OAuth client that issued the Android
     * "oauth" sessions is used, so a session copied from Android can still be refreshed with the same client.
     */
    val tidalDeviceClient: TidalClientCredentials?
        get() = client("ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID", "ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET")
            ?: client("ARCHIVETUNE_TIDAL_CLIENT_ID", "ARCHIVETUNE_TIDAL_CLIENT_SECRET")

    val tidalPkceClient: TidalClientCredentials?
        get() = client("ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID", "ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET")

    private fun client(idName: String, secretName: String): TidalClientCredentials? {
        val id = value(idName)
        val secret = value(secretName)
        return if (id.isBlank() || secret.isBlank()) null else TidalClientCredentials(id, secret)
    }

    private fun built(name: String): String = when (name) {
        "ARCHIVETUNE_TIDAL_WEB_TOKEN" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_WEB_TOKEN
        "ARCHIVETUNE_TIDAL_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_CLIENT_ID
        "ARCHIVETUNE_TIDAL_CLIENT_SECRET" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_CLIENT_SECRET
        "ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID
        "ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET
        "ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID
        "ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET
        "ARCHIVETUNE_JIOSAAVN_DES_KEY" -> DesktopBuildCredentials.ARCHIVETUNE_JIOSAAVN_DES_KEY
        "ARCHIVETUNE_LASTFM_API_KEY" -> DesktopBuildCredentials.ARCHIVETUNE_LASTFM_API_KEY
        "ARCHIVETUNE_LASTFM_API_SECRET" -> DesktopBuildCredentials.ARCHIVETUNE_LASTFM_API_SECRET
        "ARCHIVETUNE_LISTENER_PRESENCE_URL" -> DesktopBuildCredentials.ARCHIVETUNE_LISTENER_PRESENCE_URL
        else -> ""
    }
}
