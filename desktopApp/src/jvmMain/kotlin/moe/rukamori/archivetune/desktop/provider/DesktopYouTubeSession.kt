package moe.rukamori.archivetune.desktop.provider

import moe.rukamori.archivetune.shared.domain.settings.DataSyncIdKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeCookieKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.VisitorDataKey

/**
 * The persisted personal YouTube sign-in as a player request carries it. [loginValue] is the login cookie value the
 * SAPISIDHASH signs. The values never appear in a log line.
 */
internal class DesktopYouTubeSignIn(
    val cookie: String,
    val loginValue: String,
    val visitorData: String?,
    val dataSyncId: String?,
) {
    override fun toString(): String = "DesktopYouTubeSignIn(redacted)"
}

/**
 * The sign-in the settings host committed, read from the same keys the Android preferences use. A cookie without a
 * login value is a visitor cookie, not a sign-in, so it yields null.
 */
internal fun PreferenceSnapshot?.youTubeSignIn(): DesktopYouTubeSignIn? {
    val snapshot = this ?: return null
    val cookie = snapshot[InnerTubeCookieKey]?.takeIf(String::isNotBlank) ?: return null
    val loginValue = youTubeLoginCookieValue(cookie) ?: return null
    return DesktopYouTubeSignIn(
        cookie = cookie,
        loginValue = loginValue,
        visitorData = snapshot[VisitorDataKey]?.takeIf(String::isNotBlank),
        dataSyncId = snapshot[DataSyncIdKey]?.takeIf(String::isNotBlank),
    )
}
