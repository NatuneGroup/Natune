package moe.rukamori.archivetune.shared.data.platform.ios

import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem

/**
 * One incoming link the iOS app accepted, classified for the shell. Nothing is fetched here: a link is parsed and
 * routed the way the Android host routes the same links, never contacted as a request.
 */
sealed interface IosDeepLink {
    /** `archivetune://open/<route>` — a page link; the shared router resolves it and the same link refusals apply. */
    data class AppRoute(val route: String) : IosDeepLink

    /** `archivetune://login?url=` — the sign-in return Android routes to its login page. */
    data class Login(val url: String?) : IosDeepLink

    /** A listen-together invite: custom scheme or one of the legacy/https invite hosts, carrying the room code. */
    data class ListenTogetherInvite(val code: String) : IosDeepLink
}

/**
 * The iOS deep-link intake. The custom scheme mirrors the Android manifest's `archivetune` handling and the desktop's
 * open-host contract. Invite links accept exactly the hosts the Android app's `ListenTogetherServers.inviteHosts`
 * accepts — the live Hugging Face server and the retired `vivimusic-listen-together.onrender.com`, which is an
 * invite host only and is never contacted. An https link to one of those hosts cannot be intercepted on iOS (there is
 * no apple-app-site-association file on domains the app does not own), so this intake sees it only through the app's
 * own custom scheme or a future paste path; the classification is still identical when such a link does arrive.
 */
object IosDeepLinks {
    /** The hosts a listen link may name — the live invite server and the retired one kept as an invite host. */
    private val INVITE_HOSTS = setOf(
        "devilmi-vivi-music-listen-together.hf.space",
        "vivimusic-listen-together.onrender.com",
    )

    /**
     * Classifies [raw] or returns null when the link is not for this app. `archivetune://open/<route>` and
     * `archivetune://login` match first; an invite is recognized exactly as the Android host recognizes it — a
     * "listen" host or a "listen" first path segment or an invite host — and the room code is `?code`, `?room` or
     * the second path segment, in that order.
     */
    fun classify(raw: String): IosDeepLink? {
        val components = NSURLComponents.componentsWithString(raw) ?: return null
        val scheme = components.scheme?.lowercase() ?: return null
        val host = components.host?.lowercase()
        val segments = components.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = components.queryItems.orEmpty()
            .filterIsInstance<NSURLQueryItem>()
            .associate { it.name to it.value.orEmpty() }
        if (scheme == "archivetune") {
            when (host) {
                "open" -> {
                    val route = components.path.orEmpty().removePrefix("/") +
                        (components.percentEncodedQuery?.let { "?$it" } ?: "")
                    return route.takeUnless { it.isEmpty() }?.let(IosDeepLink::AppRoute)
                }
                "login" -> return IosDeepLink.Login(query["url"])
            }
        }
        val isListenLink = segments.firstOrNull() == "listen" ||
            host == "listen" ||
            (host != null && host in INVITE_HOSTS)
        if (!isListenLink) return null
        val code = query["code"] ?: query["room"] ?: segments.getOrNull(1)
        return if (code.isNullOrBlank()) null else IosDeepLink.ListenTogetherInvite(code)
    }
}
