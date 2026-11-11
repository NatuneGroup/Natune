package moe.rukamori.archivetune.lyrics

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.EnableLyprioLyricsKey
import moe.rukamori.archivetune.shared.data.lyrics.LyprioEndpoint
import moe.rukamori.archivetune.shared.data.lyrics.resolveLyprioEndpoint
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.withAppProxy
import moe.rukamori.archivetune.shared.data.lyrics.LyprioLyricsProvider as SharedLyprioLyricsProvider

/**
 * Metrolist's hosted Lyprio lyrics server, through the shared client. Its endpoint comes only from the build
 * (BuildConfig.LYPRIO_BASE_URL / LYPRIO_HMAC_KEY) — the service is run by the Metrolist developers and has no
 * user setting, so those values never reach preferences, logs or backups. Enabled by default like Metrolist;
 * the settings toggle is the only control. [isEnabled] re-resolves the endpoint right before every lookup, and
 * the shared provider is kept while it is unchanged because it remembers a server without protocol 2.
 */
object LyprioLyricsProvider : LyricsProvider {
    override val name = SharedLyprioLyricsProvider.NAME

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
    private var current: SharedLyprioLyricsProvider? = null

    @Volatile
    private var currentEndpoint: LyprioEndpoint? = null

    override fun isEnabled(context: Context): Boolean {
        // Enabled unless the user opted out: Metrolist ships its bundled providers on, Lyprio first.
        if (context.dataStore[EnableLyprioLyricsKey] == false) return false
        val endpoint =
            resolveLyprioEndpoint(
                BuildConfig.LYPRIO_BASE_URL,
                BuildConfig.LYPRIO_HMAC_KEY,
            )
        synchronized(this) {
            if (endpoint != currentEndpoint) {
                currentEndpoint = endpoint
                current = endpoint?.let { SharedLyprioLyricsProvider(client, it) }
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
            ?: Result.failure(IllegalStateException("Lyprio is not configured"))

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
