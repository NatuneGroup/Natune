package moe.rukamori.archivetune.shared.domain.customartwork

/**
 * The artwork identity of a local playlist's custom cover. A playlist row has no provider artwork of its own, so the
 * cover is keyed on this stable pseudo-URL instead of a remote thumbnail. The scheme is deliberately not http, so the
 * artwork identity helper returns it unchanged and every surface resolves the same stored file.
 */
private const val PLAYLIST_COVER_SCHEME = "local-playlist-cover"

/** The pseudo-URL a local playlist's custom cover is stored and resolved under. */
fun localPlaylistCoverUri(playlistId: String): String = "$PLAYLIST_COVER_SCHEME://$playlistId"

/** Whether [uri] is a local playlist cover identity rather than a provider artwork URL. */
fun isLocalPlaylistCoverUri(uri: String?): Boolean = uri != null && uri.startsWith("$PLAYLIST_COVER_SCHEME://")
