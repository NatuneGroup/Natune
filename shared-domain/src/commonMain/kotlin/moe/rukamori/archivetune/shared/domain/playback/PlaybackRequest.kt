package moe.rukamori.archivetune.shared.domain.playback

import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef

/**
 * One queue occurrence to play. [occurrenceId] distinguishes two copies of the same media, which
 * [mediaId] alone cannot.
 */
data class PlaybackRequest(
    val occurrenceId: String,
    val mediaId: String,
    val locator: PlaybackLocator,
) {
    init {
        require(occurrenceId.isNotBlank()) { "occurrenceId must not be blank" }
        require(mediaId.isNotBlank()) { "mediaId must not be blank" }
    }
}

/**
 * Where the audio comes from. App-internal routes such as `telegram://` or `tidal-dash://` are not
 * locators: the app resolves them to one of these before any engine sees them.
 */
sealed interface PlaybackLocator {
    /** A file on this machine. The path must be absolute. */
    data class LocalFile(val path: String) : PlaybackLocator {
        init {
            require(path.isNotBlank()) { "local file path must not be blank" }
        }
    }

    /**
     * An HTTP or HTTPS resource. The engine detects progressive audio, HLS or DASH from the bytes,
     * so the URL itself carries no format promise. [headers] must accompany every request for [url];
     * public resources leave them empty, which keeps the header-free locator unchanged.
     */
    data class Remote(val url: String, val headers: PlaybackHeaders = PlaybackHeaders.NONE) : PlaybackLocator {
        init {
            require(url.isNotBlank()) { "remote url must not be blank" }
        }
    }

    /**
     * A Deezer CDN stream whose every third 2048-byte chunk is Blowfish-encrypted under a key derived from
     * [trackId]. Only a host lease that owns the chunk decryptor may read it. The track id therefore travels with
     * the URL: a plain [Remote] would hand ciphertext to an engine that cannot decrypt it.
     */
    data class DeezerEncrypted(
        val trackId: String,
        val url: String,
        val headers: PlaybackHeaders = PlaybackHeaders.NONE,
    ) : PlaybackLocator {
        init {
            require(trackId.isNotBlank()) { "deezer track id must not be blank" }
            require(url.isNotBlank()) { "deezer url must not be blank" }
        }

        /** The URL can carry a time-limited token, so it is kept out of logs and diagnostics. */
        override fun toString(): String =
            "DeezerEncrypted(trackId=$trackId, url=REDACTED, headers=${headers.names})"
    }

    /**
     * A Telegram channel track that TDLib serves. The locator names the track and never a URL: the host resolves the
     * current TDLib file and reads it through a non-HTTP lease, so no network origin or header policy applies to it.
     */
    data class TelegramFile(val track: TelegramTrackRef) : PlaybackLocator

    /**
     * Audio that the host prepared from a native recipe or a TIDAL manifest, read through the host's own lease. The
     * handle is the only way in and there is no URL, so a refused preparation can never reach an engine as a
     * progressive locator. One handle is one view, so a second play needs a fresh preparation.
     */
    data class PreparedPlaintext(val handle: PreparedPlaintextHandle) : PlaybackLocator {
        override fun toString(): String =
            "PreparedPlaintext(mimeType=${handle.mimeType}, codec=${handle.codec}, lengthBytes=${handle.lengthBytes})"
    }
}
