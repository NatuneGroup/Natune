package moe.rukamori.archivetune.playback

import java.io.IOException
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import timber.log.Timber

/** Spotify engines share a cache key but write incompatible encodings and account-bound bytes. */
internal object SpotifyAudioCache {
    const val METHOD_METADATA = "archivetune-spotify-method"

    fun align(cache: Cache, key: String, tag: String, onDiscard: () -> Unit) {
        runCatching {
            val stored = cache.getContentMetadata(key).get(METHOD_METADATA, null as String?)
            if (stored == tag) return
            if (stored != null || cache.getCachedSpans(key).isNotEmpty()) {
                cache.removeResource(key)
                onDiscard()
            }
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(METHOD_METADATA, tag))
        }.getOrElse { error ->
            Timber.tag("MusicService").w("Could not align Spotify audio cache category=%s", error.javaClass.simpleName)
            // Continuing would let the new stream read bytes owned by a different engine,
            // account or encoding. Reject the candidate through the existing source chain.
            throw IOException("Spotify audio cache alignment failed")
        }
    }
}
