
package moe.rukamori.archivetune.constants

enum class HistorySource {
    /** What this device played, out of the app's own database. */
    LOCAL,

    /** The signed-in YouTube Music account's watch history. */
    REMOTE,

    /**
     * Spotify's play history. Capped at the last 50 plays by the endpoint itself — Spotify offers
     * no way further back — so this pill shows a window, not an archive.
     */
    SPOTIFY,
}
