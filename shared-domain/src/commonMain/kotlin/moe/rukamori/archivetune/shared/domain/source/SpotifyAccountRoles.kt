package moe.rukamori.archivetune.shared.domain.source

/** Which of the two Spotify sign-ins a feature asks for. */
enum class SpotifyAccountRole {
    /** The main account: catalog metadata, playlists, saves and likes. It never streams through the unofficial path. */
    LIBRARY,

    /** The account the streaming engine plays as. Which sign-in fills this role depends on the engine's binding. */
    STREAM,
}

/**
 * How the app plays Spotify audio. Stored by enum name in preferences; an unknown stored value reads as [Default].
 */
enum class SpotifyStreamEngine {
    /**
     * The existing experimental source (the web-player and desktop resolve methods). It registers a virtual
     * Connect device and issues play commands, so it runs only on a dedicated second account that carries the
     * ban risk.
     */
    UNOFFICIAL,

    /**
     * A Spotify Connect device-style client speaking the librespot protocol. Presenting like an ordinary
     * Connect device is the lowest-ban-risk way to stream, so this engine binds the library (main) account.
     * It requires Spotify Premium.
     */
    LIBRESPOT,
    ;

    companion object {
        val Default: SpotifyStreamEngine = UNOFFICIAL

        fun fromPreference(value: String?): SpotifyStreamEngine =
            entries.firstOrNull { it.name == value } ?: Default
    }
}

/** Which sign-in the [SpotifyAccountRole.STREAM] role binds to under a given engine. */
enum class SpotifyStreamAccountBinding {
    /** A second, dedicated account signed in through its own cookie profile (the `SpotifyStream*` session). */
    DEDICATED,

    /** The library (catalog) account itself; the library and stream roles resolve to the same sign-in. */
    LIBRARY,
}

/**
 * The stream role runs on the dedicated alt account for [SpotifyStreamEngine.UNOFFICIAL] and on the library
 * account for [SpotifyStreamEngine.LIBRESPOT]. Only Librespot may bind both roles to one account.
 */
fun SpotifyStreamEngine.streamAccountBinding(): SpotifyStreamAccountBinding = when (this) {
    SpotifyStreamEngine.UNOFFICIAL -> SpotifyStreamAccountBinding.DEDICATED
    SpotifyStreamEngine.LIBRESPOT -> SpotifyStreamAccountBinding.LIBRARY
}

/**
 * Engine-aware stand-down for the streaming session. The same-account refusal protects the unofficial
 * engine only: Librespot binds the library account by design, so an identical sign-in is expected there
 * and never counts as a collision.
 */
fun SpotifyStreamEngine.streamingStandsDown(
    catalogSpDc: String,
    streamingSpDc: String,
    streamingUserId: String,
    catalogUserId: () -> String?,
): Boolean =
    this == SpotifyStreamEngine.UNOFFICIAL &&
        SpotifyStreamingPolicy.streamingStandsDown(catalogSpDc, streamingSpDc, streamingUserId, catalogUserId)
