package moe.rukamori.archivetune.lyrics

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.EnableLyprioLyricsKey
import moe.rukamori.archivetune.constants.LyprioEndpointKey
import moe.rukamori.archivetune.constants.LyprioSigningKeyKey
import moe.rukamori.archivetune.shared.data.lyrics.LyprioEndpoint
import moe.rukamori.archivetune.shared.data.lyrics.resolveLyprioEndpoint
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.withAppProxy
import moe.rukamori.archivetune.shared.data.lyrics.LyprioLyricsProvider as SharedLyprioLyricsProvider

/**
 * The user's self-hosted Lyprio server, through the shared client. The server URL and signing key are read when the
 * helper asks [isEnabled], which it does right before every lookup, so an edit in Settings applies to the next song.
 * When the user has entered neither value, the pair baked in at build time (BuildConfig.LYPRIO_BASE_URL /
 * LYPRIO_HMAC_KEY) is used instead; those values never reach preferences, logs or backups. The shared provider is
 * kept while the endpoint is unchanged because it remembers a server without protocol 2.
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
        if (context.dataStore[EnableLyprioLyricsKey] != true) return false
        val endpoint =
            resolveLyprioEndpoint(
                context.dataStore[LyprioEndpointKey].orEmpty(),
                context.dataStore[LyprioSigningKeyKey].orEmpty(),
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
        callback: (String) -> Unit,
    ) {
        current?.getAllLyrics(id, title, artist, album, duration, callback = callback)
    }
}
