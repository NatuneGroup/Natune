/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.settings

/** Shortest ARL the baseline accepts before the host asks Deezer to verify it. */
const val DeezerArlMinimumLength = 32

/** Characters that java.net.URI rejects; an instance containing one is dropped before any probe. */
private const val DeezerInstanceIllegalCharacters = "\"<>\\^`{}|"

/** Strips an optional `arl=` prefix and surrounding whitespace, as the baseline sign-in dialog does. */
fun normalizeDeezerArl(raw: String): String = raw.trim().removePrefix("arl=").trim()

fun isPlausibleDeezerArl(raw: String): Boolean = normalizeDeezerArl(raw).length >= DeezerArlMinimumLength

/** The stored account name is the only signed-in marker, as in the baseline page. */
fun PreferenceSnapshot.deezerAccountName(): String = this[DeezerAccountNameKey, ""]

fun PreferenceSnapshot.deezerSignedIn(): Boolean = deezerAccountName().isNotEmpty()

/** Normalized user instances in saved order. */
fun PreferenceSnapshot.deezerInstanceList(): List<String> = parseDeezerInstances(this[DeezerInstancesKey, ""])

/** Splits pasted text on line breaks, commas and spaces, then normalizes each entry and drops duplicates. */
fun parseDeezerInstances(raw: String): List<String> =
    raw.split('\n', '\r', ',', ' ').mapNotNull(::normalizeDeezerInstance).distinct()

/**
 * Trims trailing slashes and adds https when no scheme is given. An entry needs a host and must avoid the characters
 * java.net.URI rejects, which keeps the accepted set for ordinary base URLs the same as the baseline parser.
 */
private fun normalizeDeezerInstance(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it in DeezerInstanceIllegalCharacters }) return null
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    val authority = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val hostPort = authority.substringAfterLast('@')
    val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') else hostPort.substringBefore(':')
    return withScheme.takeIf { host.trim('[', ']').isNotEmpty() }
}

/**
 * Stores a host-verified ARL with its account name and premium flag, and turns Deezer on, in one edit. The ARL is a
 * credential: [DeezerArlKey] is vault-routed by the logical adapter, so it never reaches an ordinary edit.
 */
fun PreferenceEditor.commitDeezerArlSignIn(arl: String, accountName: String, premium: Boolean) {
    val normalized = normalizeDeezerArl(arl)
    require(normalized.length >= DeezerArlMinimumLength) { "Deezer ARL is too short to commit" }
    require(accountName.isNotBlank()) { "Deezer account name is required before an ARL is committed" }
    this[DeezerArlKey] = normalized
    commitDeezerAccountFacts(accountName, premium)
}

/**
 * Writes the display state of a verified account and turns Deezer on. The ARL itself belongs to the account commit, which
 * writes it together with the verified identity, so this edit never touches it.
 */
fun PreferenceEditor.commitDeezerAccountFacts(accountName: String, premium: Boolean) {
    require(accountName.isNotBlank()) { "Deezer account name is required before an account is shown" }
    this[DeezerAccountNameKey] = accountName
    this[DeezerAccountPremiumKey] = premium
    this[DeezerEnabledKey] = true
}

/** Clears the ARL and its display state together. The host drops its in-memory ARL before this commit. */
fun PreferenceEditor.clearDeezerSession() {
    this[DeezerArlKey] = ""
    clearDeezerAccountFacts()
}

/** Clears the display state only. The account sign-out has already removed the ARL and the identity in its own edit. */
fun PreferenceEditor.clearDeezerAccountFacts() {
    this[DeezerAccountNameKey] = ""
    this[DeezerAccountPremiumKey] = false
}

/** Saves the normalized instance list. A non-empty list also turns Deezer on, as the baseline dialog does. */
fun PreferenceEditor.setDeezerInstances(raw: String) {
    val normalized = parseDeezerInstances(raw).joinToString("\n")
    this[DeezerInstancesKey] = normalized
    if (normalized.isNotEmpty()) this[DeezerEnabledKey] = true
}
