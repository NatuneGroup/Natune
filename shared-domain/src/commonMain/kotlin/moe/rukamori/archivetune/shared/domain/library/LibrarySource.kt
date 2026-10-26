package moe.rukamori.archivetune.shared.domain.library

/** Which service the Library's sections read from. */
enum class LibrarySource {
    YTM,
    SPOTIFY,
    ;

    /**
     * This choice as the Library can actually honour it.
     *
     * The stored choice belongs to the user, while the sections read the resolved value. Spotify without a usable
     * session resolves to YTM, so no section asks a service that is not signed in. The stored Spotify choice itself
     * survives a sign-out, so it is never rewritten behind the user's back.
     */
    fun resolved(spotifyAvailable: Boolean): LibrarySource = if (this == SPOTIFY && !spotifyAvailable) YTM else this
}
