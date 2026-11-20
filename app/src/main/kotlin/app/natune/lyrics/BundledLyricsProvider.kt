package app.natune.lyrics

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import app.natune.BuildConfig
import app.natune.constants.EnableBundledLyricsKey
import app.natune.shared.data.lyrics.BundledEndpoint
import app.natune.shared.data.lyrics.resolveBundledEndpoint
import app.natune.utils.dataStore
import app.natune.utils.get
import app.natune.utils.withAppProxy
import app.natune.shared.data.lyrics.BundledLyricsProvider as SharedBundledLyricsProvider

/**
 * Metrolist's hosted Bundled lyrics server, through the shared client. Its endpoint comes only from the build
 * (BuildConfig.BUNDLED_BASE_URL / BUNDLED_HMAC_KEY) — the service is run by the Metrolist developers and has no
 * user setting, so those values never reach preferences, logs or backups. Enabled by default like Metrolist;
 * the settings toggle is the only control. [isEnabled] re-resolves the endpoint right before every lookup, and
 * the shared provider is kept while it is unchanged because it remembers a server without protocol 2.
 */
object BundledLyricsProvider : LyricsProvider {
    override val name = SharedBundledLyricsProvider.NAME

    private val client by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    withAppProxy()
                }
            }
            expectSuccess = false
            followRedirects = false
        }
    }

    @Volatile
    private var current: SharedBundledLyricsProvider? = null

    @Volatile
    private var currentEndpoint: BundledEndpoint? = null

    override fun isEnabled(context: Context): Boolean {
        // Enabled unless the user opted out: Metrolist ships its bundled providers on, Bundled first.
        if (context.dataStore[EnableBundledLyricsKey] == false) return false
        val endpoint =
            resolveBundledEndpoint(
                BuildConfig.BUNDLED_BASE_URL,
                BuildConfig.BUNDLED_HMAC_KEY,
            )
        synchronized(this) {
            if (endpoint != currentEndpoint) {
                currentEndpoint = endpoint
                current = endpoint?.let { SharedBundledLyricsProvider(client, it) }
            }
        }
        return endpoint != null
    }

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        current?.getLyrics(id, title, artist, album, duration)
            ?: Result.failure(IllegalStateException("Bundled is not configured"))

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        onFailure: (Throwable) -> Unit,
        callback: (String) -> Unit,
    ) {
        current?.getAllLyrics(id, title, artist, album, duration, callback = callback)
    }
}
