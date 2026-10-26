package moe.rukamori.archivetune.shared.domain.settings

/** Subscription state the baseline stores by name. Anything unrecognized reads as UNKNOWN. */
enum class TidalSubscriptionStatus {
    UNKNOWN,
    FREE,
    PREMIUM;

    companion object {
        fun fromStoredName(value: String?): TidalSubscriptionStatus = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** Outcome of the host's last probe of one HiFi instance. */
enum class TidalInstanceStatus { HEALTHY, PREVIEW_ONLY, UNREACHABLE }

/** Host probe result for one instance. [latencyMs] is null when the probe did not measure a round trip. */
data class TidalInstanceHealth(val status: TidalInstanceStatus, val latencyMs: Long? = null)

/**
 * A session the host verified: a pasted refresh token it exchanged, or a completed web sign-in. The access and refresh
 * tokens are credentials and reach storage only through the logical vault adapter. The rest are display facts.
 */
data class TidalSessionFacts(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val authFlow: String,
    val userId: Long? = null,
    val countryCode: String? = null,
    val accountName: String? = null,
    val subscription: TidalSubscriptionStatus? = null,
)

/** [TidalAuthFlowKey] values. The refresh path picks its client from them, as the Android account manager does. */
const val TidalAuthFlowDevice = "oauth"
const val TidalAuthFlowPkce = "pkce"
const val TidalAuthFlowWebCapture = "webcapture"

/** The stored access token is the signed-in marker, as in the baseline page. */
fun PreferenceSnapshot.tidalSignedIn(): Boolean = this[TidalAccessTokenKey, ""].isNotBlank()

fun PreferenceSnapshot.tidalAccountName(): String = this[TidalAccountNameKey, ""].trim()

fun PreferenceSnapshot.tidalSubscription(): TidalSubscriptionStatus =
    TidalSubscriptionStatus.fromStoredName(this[TidalSubscriptionKey, TidalSubscriptionStatus.UNKNOWN.name])

fun PreferenceSnapshot.tidalNeedsRelogin(): Boolean = this[TidalNeedsReloginKey, false]

fun PreferenceSnapshot.tidalCountryCode(): String = this[TidalCountryCodeKey, ""].trim()

/** Zero means no user id is known. */
fun PreferenceSnapshot.tidalUserId(): Long = this[TidalUserIdKey, 0L]

/** Stored instance URLs in saved order. The built-in default list is empty, so blank storage means no instances. */
fun PreferenceSnapshot.tidalInstanceList(): List<String> =
    this[TidalInstancesKey, ""].split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Saves the instance list without duplicates, one URL per line. An empty list is stored as blank, which reads as "no
 * instances", so the list never reverts to hidden defaults.
 */
fun PreferenceEditor.setTidalInstances(instances: List<String>) {
    this[TidalInstancesKey] = instances.distinct().joinToString("\n")
}

/**
 * Commits a host-verified session in one edit. The access and refresh tokens are vault-routed by the logical adapter.
 * Account name and subscription change only when the host supplies them. A completed session always clears the
 * needs-relogin flag, as the baseline token flows do.
 */
fun PreferenceEditor.commitTidalSession(session: TidalSessionFacts) {
    require(session.accessToken.isNotBlank()) { "Tidal access token is required before a session is committed" }
    require(session.refreshToken.isNotBlank()) { "Tidal refresh token is required before a session is committed" }
    this[TidalAccessTokenKey] = session.accessToken
    this[TidalRefreshTokenKey] = session.refreshToken
    commitTidalSessionFacts(session)
}

/**
 * Writes only the display facts of a verified session: expiry, flow, user, country, name and subscription. The tokens
 * belong to the account commit, which writes them together with the verified identity, so this edit never touches them.
 */
fun PreferenceEditor.commitTidalSessionFacts(session: TidalSessionFacts) {
    this[TidalTokenExpiryKey] = session.expiresAtMillis
    this[TidalAuthFlowKey] = session.authFlow
    session.userId?.let { this[TidalUserIdKey] = it }
    session.countryCode?.takeIf(String::isNotBlank)?.let { this[TidalCountryCodeKey] = it }
    session.accountName?.takeIf(String::isNotBlank)?.let { this[TidalAccountNameKey] = it }
    session.subscription?.let { this[TidalSubscriptionKey] = it.name }
    this[TidalNeedsReloginKey] = false
}

/**
 * Clears the tokens and every display fact in one edit, and returns subscription to UNKNOWN. The host drops its
 * in-memory tokens before this commit, so the session never outlives its stored copy.
 */
fun PreferenceEditor.clearTidalSession() {
    this[TidalAccessTokenKey] = ""
    this[TidalRefreshTokenKey] = ""
    clearTidalSessionFacts()
}

/** Clears the display facts only. The account sign-out has already removed the tokens and the identity in its own edit. */
fun PreferenceEditor.clearTidalSessionFacts() {
    this[TidalTokenExpiryKey] = 0L
    this[TidalAuthFlowKey] = ""
    this[TidalAccountNameKey] = ""
    this[TidalCountryCodeKey] = ""
    this[TidalUserIdKey] = 0L
    this[TidalNeedsReloginKey] = false
    this[TidalSubscriptionKey] = TidalSubscriptionStatus.UNKNOWN.name
}
