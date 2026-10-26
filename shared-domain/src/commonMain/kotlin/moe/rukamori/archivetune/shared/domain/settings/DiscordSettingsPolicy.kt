/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.settings

/**
 * Discord Rich Presence settings: stored choices, baseline defaults, saved-account facts and the one-edit commits for
 * sign-in and sign-out. The PKCE exchange and the Gateway are host-owned. The tokens use the vault-routed `discord`
 * credential scope, so this file describes them without ever writing them outside a [PreferenceEditor] transition.
 */

/** Activity text sources. The stored value is the enum name, the same form the baseline persists. */
enum class DiscordActivitySource { ARTIST, ALBUM, SONG, APP }

/** Profile facts of a Discord account. [avatarUrl] is null when Discord reports no avatar. */
data class DiscordAccountFacts(val username: String, val displayName: String, val avatarUrl: String?)

/**
 * Tokens and expiry from one completed authorization. [expiresAtMillis] is 0 when Discord reports no expiry. The
 * string form is redacted, so a log line or debugger never prints a token.
 */
class DiscordSessionFacts(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val account: DiscordAccountFacts?,
) {
    override fun toString(): String = "DiscordSessionFacts(redacted)"
}

/** One verification of the saved token, performed by the host. Rejected and Unverified stay distinct on the page. */
sealed interface DiscordAccountCheck {
    data class Verified(val account: DiscordAccountFacts) : DiscordAccountCheck
    data object Rejected : DiscordAccountCheck
    data object Unverified : DiscordAccountCheck
}

/** The saved authorization as the settings page presents it. */
enum class DiscordSavedAuthorization { MISSING, CHECKING, LINKED, EXPIRED, REJECTED, UNVERIFIED }

/** Baseline defaults. A key without a stored value reads these, as the shipped Discord pages did. */
object DiscordSettingsDefaults {
    const val RICH_PRESENCE_ENABLED = true
    const val PRESENCE_STATUS = "online"
    const val PLATFORM = "android"
    val ACTIVITY_NAME_SOURCE = DiscordActivitySource.APP
    val ACTIVITY_DETAILS_SOURCE = DiscordActivitySource.SONG
    val ACTIVITY_STATE_SOURCE = DiscordActivitySource.ARTIST
    const val SHOW_WHEN_PAUSED = false
    const val ACTIVITY_TYPE = "LISTENING"
    const val LARGE_IMAGE = "thumbnail"
    const val LARGE_TEXT = "album"
    const val SMALL_IMAGE = "artist"
    const val BUTTON1_ENABLED = true
    const val BUTTON1_LABEL = "Listen on YouTube Music"
    const val BUTTON1_URL_SOURCE = "songurl"
    const val BUTTON2_ENABLED = true
    const val BUTTON2_LABEL = "Go to ArchiveTune"
    const val BUTTON2_URL_SOURCE = "custom"
    const val BUTTON2_CUSTOM_URL = "https://github.com/rukamori/ArchiveTune"
    const val TRANSLATOR_CONTEXTS = "{song}, {artist}, {album}"
    const val TRANSLATOR_TARGET = "ENGLISH"
}

/**
 * Persisted choice values. A stored value outside a list displays as the default, as the baseline does, and the
 * page never writes a value it did not offer.
 */
val DiscordPresenceStatusValues: List<String> = listOf("online", "dnd", "idle", "streaming")
val DiscordPlatformValues: List<String> = listOf("desktop", "xbox", "samsung", "ios", "android", "embedded", "ps4", "ps5")
val DiscordActivityTypeValues: List<String> = listOf("PLAYING", "STREAMING", "LISTENING", "WATCHING", "COMPETING")
val DiscordLargeImageValues: List<String> = listOf("thumbnail", "artist", "appicon", "custom")
val DiscordSmallImageValues: List<String> = listOf("thumbnail", "artist", "appicon", "custom", "dontshow")
val DiscordLargeTextValues: List<String> = listOf("song", "artist", "album", "app", "custom", "dontshow")
val DiscordButtonUrlSourceValues: List<String> = listOf("songurl", "artisturl", "albumurl", "custom")

/** Whether a token is stored. The page needs only this and the expiry; the token itself is never displayed. */
fun PreferenceSnapshot.discordTokenPresent(): Boolean = this[DiscordTokenKey, ""].isNotBlank()

/** Expiry in epoch milliseconds, or 0 when none is stored. */
fun PreferenceSnapshot.discordTokenExpiresAt(): Long = this[DiscordTokenExpiresAtKey, 0L]

/** A stored token past its expiry is never treated as a connected account. */
fun PreferenceSnapshot.discordAccessExpired(nowMillis: Long): Boolean {
    val expiresAt = discordTokenExpiresAt()
    return discordTokenPresent() && expiresAt > 0L && nowMillis >= expiresAt
}

/** Stored profile, trimmed. Blank fields mean no profile has been verified yet. */
fun PreferenceSnapshot.discordAccount(): DiscordAccountFacts = DiscordAccountFacts(
    username = this[DiscordUsernameKey, ""].trim(),
    displayName = this[DiscordNameKey, ""].trim(),
    avatarUrl = this[DiscordAvatarUrlKey, ""].trim().takeIf { it.isNotEmpty() },
)

fun PreferenceSnapshot.discordRichPresenceEnabled(): Boolean =
    this[EnableDiscordRPCKey, DiscordSettingsDefaults.RICH_PRESENCE_ENABLED]

/** A stored activity source, or [default] when the stored name is absent or unknown. */
fun PreferenceSnapshot.discordActivitySource(
    key: StringPreferenceKey,
    default: DiscordActivitySource,
): DiscordActivitySource {
    val stored = this[key, default.name]
    return DiscordActivitySource.entries.firstOrNull { it.name == stored } ?: default
}

/**
 * Writes one completed authorization in a single edit: both tokens (vault-routed), the expiry and the profile. A null
 * account clears the stored profile, so a previous user's name is never shown for the new session.
 */
fun PreferenceEditor.commitDiscordSignIn(session: DiscordSessionFacts) {
    this[DiscordTokenKey] = session.accessToken.trim().takeIf { it.isNotEmpty() }
    this[DiscordRefreshTokenKey] = session.refreshToken?.trim()?.takeIf { it.isNotEmpty() }
    this[DiscordTokenExpiresAtKey] = session.expiresAtMillis.takeIf { it > 0L }
    commitDiscordProfile(session.account)
}

/** Writes the profile a successful verification reported. Blank optional fields are stored as empty. */
fun PreferenceEditor.commitDiscordProfile(account: DiscordAccountFacts?) {
    this[DiscordUsernameKey] = account?.username?.trim().orEmpty()
    this[DiscordNameKey] = account?.displayName?.trim().orEmpty()
    this[DiscordAvatarUrlKey] = account?.avatarUrl?.trim().orEmpty()
}

/**
 * Removes the saved session after the host has dropped its in-memory tokens. The Rich Presence switch keeps its stored
 * value, as the baseline sign-out does.
 */
fun PreferenceEditor.clearDiscordSession() {
    this[DiscordTokenKey] = null
    this[DiscordRefreshTokenKey] = null
    this[DiscordTokenExpiresAtKey] = null
    this[DiscordUsernameKey] = null
    this[DiscordNameKey] = null
    this[DiscordAvatarUrlKey] = null
}
