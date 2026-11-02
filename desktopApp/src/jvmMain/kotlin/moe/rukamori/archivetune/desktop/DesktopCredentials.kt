package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.desktop.backup.GoogleDesktopClient
import moe.rukamori.archivetune.shared.data.lyrics.LyprioEndpoint
import moe.rukamori.archivetune.shared.data.provider.ArchivePoolEndpoints
import moe.rukamori.archivetune.shared.data.provider.TidalClientCredentials
import moe.rukamori.archivetune.shared.domain.telegram.TelegramApiCredentials

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

    /**
     * The TIDAL device-flow client. A dedicated device client wins; otherwise the OAuth client that issued the Android
     * "oauth" sessions is used, so a session copied from Android can still be refreshed with the same client.
     */
    val tidalDeviceClient: TidalClientCredentials?
        get() = client("ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID", "ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET")
            ?: client("ARCHIVETUNE_TIDAL_CLIENT_ID", "ARCHIVETUNE_TIDAL_CLIENT_SECRET")

    val tidalPkceClient: TidalClientCredentials?
        get() = client("ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID", "ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET")

    /**
     * The Telegram application identity for TDLight. The unprefixed names the Android build reads are accepted at run time
     * too. Without both values the same public Telegram Desktop identity the Android BuildConfig falls back to is used,
     * so sign-in works out of the box with just a phone number and login code.
     */
    val telegramApi: TelegramApiCredentials?
        get() {
            val id = value("ARCHIVETUNE_TELEGRAM_API_ID").ifBlank { runtime("TELEGRAM_API_ID") }.toIntOrNull() ?: 0
            val hash = value("ARCHIVETUNE_TELEGRAM_API_HASH").ifBlank { runtime("TELEGRAM_API_HASH") }
            if (id <= 0 || hash.isBlank()) {
                return TelegramApiCredentials(TELEGRAM_DESKTOP_API_ID, TELEGRAM_DESKTOP_API_HASH)
            }
            return TelegramApiCredentials(id, hash)
        }

    /** The public Telegram Desktop api_id/api_hash — the same fallback the Android BuildConfig uses. */
    private const val TELEGRAM_DESKTOP_API_ID = 2040
    private const val TELEGRAM_DESKTOP_API_HASH = "b18441a1ff607e10a989891a5462e627"

    /**
     * The ArchivePool deployment of the Playback Pool, without a trailing slash, or null when the feed is turned off. A
     * blank value takes the same public default the Android build uses; "off" turns the feed off for this launch.
     */
    val poolBaseUrl: String?
        get() {
            val configured =
                value("ARCHIVETUNE_ARCHIVEPOOL_BASE_URL")
                    .ifBlank { runtime("ARCHIVEPOOL_BASE_URL") }
                    .ifBlank { value("ARCHIVETUNE_SOURCE_PROVIDER_URL") }
                    .ifBlank { runtime("SOURCE_PROVIDER_URL") }
            if (configured.equals("off", ignoreCase = true)) return null
            return configured.ifBlank { DEFAULT_POOL_BASE_URL }.trimEnd('/').takeIf { it.startsWith("https://") }
        }

    val poolReadKey: String
        get() = value("ARCHIVETUNE_SOURCE_PROVIDER_KEY").ifBlank { runtime("SOURCE_PROVIDER_KEY") }

    val poolLegacyClientKey: String
        get() = value("ARCHIVETUNE_POOL_CLIENT_KEY").ifBlank { runtime("POOL_CLIENT_KEY") }

    /** The opt-in feedback service origin, or null when this build does not configure one. */
    val feedbackApiUrl: String?
        get() = value("ARCHIVETUNE_FEEDBACK_API_URL").ifBlank { runtime("FEEDBACK_API_URL") }
            .trimEnd('/').takeIf { it.startsWith("https://") }

    val feedbackIngestKey: String
        get() = value("ARCHIVETUNE_FEEDBACK_INGEST_KEY").ifBlank { runtime("FEEDBACK_INGEST_KEY") }

    /**
     * The Google OAuth client of type "Desktop app" for Drive app data backup. Google issues a client secret for desktop
     * clients that it does not treat as confidential; it is sent when set. Without an id, Drive backup is not configured.
     */
    val googleDesktopClient: GoogleDesktopClient?
        get() = value("ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_ID").takeIf { it.isNotBlank() }
            ?.let { GoogleDesktopClient(it, value("ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_SECRET")) }

    /**
     * The origin the Amazon Turnstile sitekey is locked to, without a trailing slash, or null when the build names none.
     * The docs pair it with the sitekey, so a blank value leaves the Amazon sign-in not configured. A value that is not
     * a plain https origin reads as blank too, because the challenge window and the instance requests both send it.
     */
    val amazonTurnstileOrigin: String?
        get() = value("ARCHIVETUNE_AMAZON_TURNSTILE_ORIGIN").trimEnd('/').takeIf { HTTPS_ORIGIN.matches(it) }

    /**
     * The built-in Lyprio lyrics server pair; the unprefixed names are accepted at run time like the
     * other desktop credentials. Both values must be present for [lyprioEndpoint] to exist, and a
     * user's own server entry in settings always wins over it. The key is never logged or persisted.
     */
    val lyprioBaseUrl: String
        get() = value("ARCHIVETUNE_LYPRIO_BASE_URL").ifBlank { runtime("LYPRIO_BASE_URL") }

    val lyprioHmacKey: String
        get() = value("ARCHIVETUNE_LYPRIO_HMAC_KEY").ifBlank { runtime("LYPRIO_HMAC_KEY") }

    val lyprioEndpoint: LyprioEndpoint?
        get() = LyprioEndpoint.parse(lyprioBaseUrl, lyprioHmacKey)

    private const val DEFAULT_POOL_BASE_URL = ArchivePoolEndpoints.FALLBACK_BASE_URL

    private val HTTPS_ORIGIN = Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$")

    private fun runtime(name: String): String =
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: System.getProperty(name)?.trim().orEmpty()

    private fun client(idName: String, secretName: String): TidalClientCredentials? {
        val id = value(idName)
        val secret = value(secretName)
        return if (id.isBlank() || secret.isBlank()) null else TidalClientCredentials(id, secret)
    }

    private fun built(name: String): String = when (name) {
        "ARCHIVETUNE_TIDAL_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_CLIENT_ID
        "ARCHIVETUNE_TIDAL_CLIENT_SECRET" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_CLIENT_SECRET
        "ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID
        "ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET
        "ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID
        "ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET" -> DesktopBuildCredentials.ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET
        "ARCHIVETUNE_JIOSAAVN_DES_KEY" -> DesktopBuildCredentials.ARCHIVETUNE_JIOSAAVN_DES_KEY
        "ARCHIVETUNE_LASTFM_API_KEY" -> DesktopBuildCredentials.ARCHIVETUNE_LASTFM_API_KEY
        "ARCHIVETUNE_LASTFM_API_SECRET" -> DesktopBuildCredentials.ARCHIVETUNE_LASTFM_API_SECRET
        "ARCHIVETUNE_LYPRIO_BASE_URL" -> DesktopBuildCredentials.ARCHIVETUNE_LYPRIO_BASE_URL
        "ARCHIVETUNE_LYPRIO_HMAC_KEY" -> DesktopBuildCredentials.ARCHIVETUNE_LYPRIO_HMAC_KEY
        "ARCHIVETUNE_LISTENER_PRESENCE_URL" -> DesktopBuildCredentials.ARCHIVETUNE_LISTENER_PRESENCE_URL
        "ARCHIVETUNE_TELEGRAM_API_ID" -> DesktopBuildCredentials.ARCHIVETUNE_TELEGRAM_API_ID
        "ARCHIVETUNE_TELEGRAM_API_HASH" -> DesktopBuildCredentials.ARCHIVETUNE_TELEGRAM_API_HASH
        "ARCHIVETUNE_ARCHIVEPOOL_BASE_URL" -> DesktopBuildCredentials.ARCHIVETUNE_ARCHIVEPOOL_BASE_URL
        "ARCHIVETUNE_SOURCE_PROVIDER_URL" -> DesktopBuildCredentials.ARCHIVETUNE_SOURCE_PROVIDER_URL
        "ARCHIVETUNE_SOURCE_PROVIDER_KEY" -> DesktopBuildCredentials.ARCHIVETUNE_SOURCE_PROVIDER_KEY
        "ARCHIVETUNE_POOL_CLIENT_KEY" -> DesktopBuildCredentials.ARCHIVETUNE_POOL_CLIENT_KEY
        "ARCHIVETUNE_FEEDBACK_API_URL" -> DesktopBuildCredentials.ARCHIVETUNE_FEEDBACK_API_URL
        "ARCHIVETUNE_FEEDBACK_INGEST_KEY" -> DesktopBuildCredentials.ARCHIVETUNE_FEEDBACK_INGEST_KEY
        "ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_ID
        "ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_SECRET" -> DesktopBuildCredentials.ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_SECRET
        "ARCHIVETUNE_SPOTIFY_DESKTOP_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_SPOTIFY_DESKTOP_CLIENT_ID
        "ARCHIVETUNE_SPOTIFY_WEB_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_SPOTIFY_WEB_CLIENT_ID
        "ARCHIVETUNE_SPOTIFY_AUDIO_IV" -> DesktopBuildCredentials.ARCHIVETUNE_SPOTIFY_AUDIO_IV
        "ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID
        "ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID" -> DesktopBuildCredentials.ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID
        "ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_SECRET" -> DesktopBuildCredentials.ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_SECRET
        "ARCHIVETUNE_AMAZON_TURNSTILE_SITEKEY" -> DesktopBuildCredentials.ARCHIVETUNE_AMAZON_TURNSTILE_SITEKEY
        "ARCHIVETUNE_AMAZON_TURNSTILE_ORIGIN" -> DesktopBuildCredentials.ARCHIVETUNE_AMAZON_TURNSTILE_ORIGIN
        "ARCHIVETUNE_POTOKEN_REQUEST_KEY" -> DesktopBuildCredentials.ARCHIVETUNE_POTOKEN_REQUEST_KEY
        "ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY" -> DesktopBuildCredentials.ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY
        else -> ""
    }
}
