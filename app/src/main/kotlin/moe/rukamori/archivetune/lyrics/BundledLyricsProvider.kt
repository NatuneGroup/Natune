package moe.rukamori.archivetune.lyrics

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.EnableBundledLyricsKey
import moe.rukamori.archivetune.constants.BundledEndpointKey
import moe.rukamori.archivetune.constants.BundledSigningKeyKey
import moe.rukamori.archivetune.shared.data.lyrics.BundledEndpoint
import moe.rukamori.archivetune.shared.data.lyrics.resolveBundledEndpoint
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.withAppProxy
import moe.rukamori.archivetune.shared.data.lyrics.BundledLyricsProvider as SharedBundledLyricsProvider

/**
 * The user's self-hosted Bundled server, through the shared client. The server URL and signing key are read when the
 * helper asks [isEnabled], which it does right before every lookup, so an edit in Settings applies to the next song.
 * When the user has entered neither value, the pair baked in at build time (BuildConfig.BUNDLED_BASE_URL /
 * BUNDLED_HMAC_KEY) is used instead; those values never reach preferences, logs or backups. The shared provider is
 * kept while the endpoint is unchanged because it remembers a server without protocol 2.
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
        if (context.dataStore[EnableBundledLyricsKey] != true) return false
        val endpoint =
            resolveBundledEndpoint(
                context.dataStore[BundledEndpointKey].orEmpty(),
                context.dataStore[BundledSigningKeyKey].orEmpty(),
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
        callback: (String) -> Unit,
    ) {
        current?.getAllLyrics(id, title, artist, album, duration, callback = callback)
    }
}
