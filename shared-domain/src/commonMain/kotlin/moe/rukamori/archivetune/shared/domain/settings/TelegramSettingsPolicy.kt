package moe.rukamori.archivetune.shared.domain.settings

/**
 * Account facts a ready Telegram session reports. The phone arrives as digits without the plus sign; the stored
 * form adds it, as the baseline settings row does.
 */
data class TelegramAccountFacts(val displayName: String?, val phoneDigits: String?)

/** Stored display name, trimmed. Blank until a ready session has been read once. */
fun PreferenceSnapshot.telegramAccountName(): String = this[TelegramAccountNameKey, ""].trim()

/** Stored phone with its plus prefix, trimmed. Blank when unknown. */
fun PreferenceSnapshot.telegramAccountPhone(): String = this[TelegramAccountPhoneKey, ""].trim()

/**
 * Writes the facts a ready session reported. A blank fact is skipped rather than written, so the stored value is
 * only ever replaced by a value the session actually returned.
 */
fun PreferenceEditor.commitTelegramAccount(facts: TelegramAccountFacts) {
    facts.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { this[TelegramAccountNameKey] = it }
    facts.phoneDigits?.trim()?.takeIf { it.isNotEmpty() }?.let { this[TelegramAccountPhoneKey] = "+$it" }
}

/**
 * Removes the local account facts after the host has signed out. The host signs out first, so a failed host sign-out
 * keeps the stored account and the row still matches the session that remains.
 */
fun PreferenceEditor.clearTelegramAccount() {
    this[TelegramAccountNameKey] = ""
    this[TelegramAccountPhoneKey] = ""
}
