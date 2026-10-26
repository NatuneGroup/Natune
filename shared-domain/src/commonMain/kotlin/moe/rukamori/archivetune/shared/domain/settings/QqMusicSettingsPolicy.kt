package moe.rukamori.archivetune.shared.domain.settings

/**
 * The preferred QQ Music tier, stored by enum name. An unknown stored value reads as [Default], so a hand-edited or
 * future value never blocks the settings page.
 */
enum class QqAudioQuality {
    LOSSLESS,
    HIGH,
    STANDARD,
    ;

    companion object {
        val Default: QqAudioQuality = HIGH

        fun fromPreference(value: String?): QqAudioQuality = entries.firstOrNull { it.name == value } ?: Default
    }
}

/**
 * The account counts as connected while a QQ account number is stored. The ticket is never read back for display; it
 * is only written by [commitQqMusicSignIn] and removed by [clearQqMusicSession].
 */
fun PreferenceSnapshot.qqMusicSignedIn(): Boolean = !this[QqMusicUinKey].isNullOrBlank()

/** The name the signed-in card shows: the nickname when one is stored, otherwise the account number. */
fun PreferenceSnapshot.qqMusicAccountLabel(): String? {
    if (!qqMusicSignedIn()) return null
    return this[QqMusicNicknameKey]?.takeIf { it.isNotBlank() } ?: this[QqMusicUinKey]
}

fun PreferenceSnapshot.qqMusicAudioQuality(): QqAudioQuality =
    QqAudioQuality.fromPreference(this[QqMusicAudioQualityKey])

fun PreferenceEditor.setQqMusicAudioQuality(quality: QqAudioQuality) {
    this[QqMusicAudioQualityKey] = quality.name
}

/**
 * Stores a finished QR sign-in in one edit. The ticket and the refresh credential are vault-routed keys, so the host
 * must commit through the logical settings store and never a plaintext file. Nothing is written unless both the account
 * number and the ticket are present, so a half-finished sign-in cannot leave an account that fails every request.
 */
fun PreferenceEditor.commitQqMusicSignIn(
    uin: String,
    musicKey: String,
    nickname: String?,
    refreshCredential: String?,
) {
    val account = uin.trim()
    val ticket = musicKey.trim()
    if (account.isEmpty() || ticket.isEmpty()) return
    this[QqMusicUinKey] = account
    this[QqMusicMusickeyKey] = ticket
    this[QqMusicNicknameKey] = nickname?.trim()?.takeIf { it.isNotEmpty() }
    this[QqMusicCredentialKey] = refreshCredential?.takeIf { it.isNotBlank() }
}

/**
 * Sign-out drops the account, its ticket and the refresh material in one edit, so no later request can carry the
 * ticket. The enabled switch and the chosen tier are kept, as the baseline sign-out keeps them.
 */
fun PreferenceEditor.clearQqMusicSession() {
    this[QqMusicMusickeyKey] = null
    this[QqMusicCredentialKey] = null
    clearQqMusicAccountFacts()
}

/**
 * Writes the display state of a verified QQ account: its number and nickname. The ticket and the refresh credential
 * belong to the account commit, which writes them together with the verified identity, so this edit never touches them.
 */
fun PreferenceEditor.commitQqMusicAccountFacts(uin: String, nickname: String?) {
    val account = uin.trim()
    if (account.isEmpty()) return
    this[QqMusicUinKey] = account
    this[QqMusicNicknameKey] = nickname?.trim()?.takeIf { it.isNotEmpty() }
}

/** Clears the display state only. The account sign-out has already removed the ticket and the identity in its own edit. */
fun PreferenceEditor.clearQqMusicAccountFacts() {
    this[QqMusicUinKey] = null
    this[QqMusicNicknameKey] = null
}
