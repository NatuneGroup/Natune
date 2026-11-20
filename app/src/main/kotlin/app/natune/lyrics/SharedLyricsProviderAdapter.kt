package app.natune.lyrics

import android.content.Context
import io.ktor.client.HttpClient
import app.natune.shared.data.lyrics.LyricsProvider as SharedLyricsProvider

/**
 * Runs a shared-data provider behind the Android [LyricsProvider] contract, on the app's proxied lyrics client.
 * The shared instance is rebuilt only when that client changes (a test override), so per-provider caches such as
 * Megalobiz's app key survive between lookups.
 */
internal class SharedLyricsProviderAdapter(
    override val name: String,
    private val enabled: (Context) -> Boolean,
    private val create: (HttpClient) -> SharedLyricsProvider,
) : LyricsProvider {
    @Volatile
    private var current: Pair<HttpClient, SharedLyricsProvider>? = null

    private fun provider(): SharedLyricsProvider {
        val client = LyricsProviderHttp.activeClient()
        current?.let { (owner, provider) -> if (owner === client) return provider }
        return create(client).also { current = client to it }
    }

    override fun isEnabled(context: Context): Boolean = enabled(context)

    override suspend fun getLyrics(id: String, title: String, artist: String, album: String?, duration: Int): Result<String> =
        provider().getLyrics(id, title, artist, album, duration)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        isrc: String?,
    ): Result<String> = provider().getLyrics(id, title, artist, album, duration, isrc)

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        onFailure: (Throwable) -> Unit,
        callback: (String) -> Unit,
    ) = provider().getAllLyrics(id, title, artist, album, duration, onFailure = onFailure, callback = callback)
}
