/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.settings

/** Amazon Music quality ladder. The stored value is the enum name, and an unknown value falls back to [Default]. */
enum class AmazonAudioQuality {
    ULTRA_HD,
    HD,
    STANDARD,
    ;

    companion object {
        val Default = HD

        fun fromPreference(value: String?): AmazonAudioQuality = values().firstOrNull { it.name == value } ?: Default
    }
}

/** Whether the stored Turnstile JWT can still be used, judged against the clock the host supplies. */
enum class AmazonJwtStatus { NEVER, EXPIRED, VALID_UNTIL }

/**
 * Mirrors the baseline rule: a blank token is never authorized, and a token whose expiry is not in the future is
 * expired. The expiry is compared with the host clock at composition, exactly as the baseline does.
 */
fun amazonJwtStatus(jwt: String, expiresAtMs: Long, nowMs: Long): AmazonJwtStatus = when {
    jwt.isBlank() -> AmazonJwtStatus.NEVER
    expiresAtMs <= nowMs -> AmazonJwtStatus.EXPIRED
    else -> AmazonJwtStatus.VALID_UNTIL
}

/** The user's list, one base URL per line with commas also accepted. Entries are trimmed, lose trailing slashes and dedupe. */
fun parseAmazonInstances(raw: String): List<String> =
    raw.split('\n', ',')
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() }
        .distinct()

fun PreferenceSnapshot.amazonInstanceList(): List<String> = parseAmazonInstances(this[AmazonInstancesKey, ""])

/** Saves the normalized instance list. */
fun PreferenceEditor.setAmazonInstances(raw: String) {
    this[AmazonInstancesKey] = parseAmazonInstances(raw).joinToString("\n")
}

/** Stores an operator bypass token, trimmed. The key is vault-routed by the logical adapter. */
fun PreferenceEditor.setAmazonBypassToken(raw: String) {
    this[AmazonBypassTokenKey] = raw.trim()
}

/**
 * Commits a Turnstile JWT and its expiry together after the host's authorization WebView returns. The JWT is
 * vault-routed; the expiry is an ordinary value, and both land in one edit so the status row never shows a mismatch.
 */
fun PreferenceEditor.commitAmazonTurnstileJwt(jwt: String, expiresAtMs: Long) {
    require(jwt.isNotBlank()) { "Amazon Turnstile JWT is blank" }
    this[AmazonTurnstileJwtKey] = jwt
    this[AmazonTurnstileJwtExpiryMsKey] = expiresAtMs
}
