/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.platform.ios

import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccount

/** The provider name that the accepted-account store keys the Telegram account under. */
internal const val IOS_TELEGRAM_PROVIDER = "telegram"

/**
 * The scope the Telegram account is accepted under. It is the store's personal scope for Telegram, the value that
 * `providerAccountScope("telegram", CredentialScope.PERSONAL)` returns, so admission and acceptance name one entry.
 */
internal const val IOS_TELEGRAM_ACCOUNT_SCOPE = "PERSONAL"

/**
 * The three native Telegram entry points of the one accepted-account store, passed to [IosTelegramClient] as callbacks.
 * The host binds them to the store's own methods, so the store that accepts a GetMe identity is the store that playback
 * re-checks before each read. Every callback is an in-memory update with no I/O, so the client may call one while it
 * holds its lock.
 */
class IosTelegramNativeAccountPort(
    /** Starts one attempt and returns its token. It must run before the GetMe request that the attempt guards. */
    val beginAttempt: () -> Long,
    /**
     * Accepts the GetMe user id for [attempt]. Returns null when the attempt is no longer the newest or the id is not
     * positive, so a late answer can never admit an account that a newer attempt or a revocation has superseded.
     */
    val acceptSession: (attempt: Long, userId: Long) -> AcceptedAccount?,
    /** Ends the native session, so every attempt begun before this call is refused and no admission still matches. */
    val revokeSession: () -> Unit,
)

/**
 * What the iOS session has verified about its Telegram account. It is kept apart from TDLib's authorization state, because
 * TDLib can report Ready while the account is still unverified. Playback waits for [Admitted], never for Ready.
 */
sealed interface IosTelegramNativeAdmission {
    /** Nothing is verified: the session has not reached Ready, its check failed or was refused, or the session has ended. */
    data object NotAdmitted : IosTelegramNativeAdmission

    /** TDLib reported Ready and the GetMe check is in flight. Telegram playback is refused until the identity is accepted. */
    data object Verifying : IosTelegramNativeAdmission

    /** GetMe returned a positive user id, and the store accepted it for the current attempt. */
    data class Admitted(val account: AcceptedAccount) : IosTelegramNativeAdmission
}
