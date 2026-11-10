package moe.rukamori.archivetune.spotify

/** Separates a refused sp_dc cookie from a refresh that only failed to get through this time. */
internal object SpotifySessionFailure {
    fun isCredentialRejection(error: Throwable): Boolean =
        (error as? Spotify.SpotifyException)?.statusCode.let { it == 401 || it == 403 }
}
