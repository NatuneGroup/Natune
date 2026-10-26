package moe.rukamori.archivetune.desktop.telegram

import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccount

/**
 * The three native Telegram entry points of the one accepted-account store, handed to [TdlightClientSession] as callbacks.
 *
 * The session never constructs an account owner of its own. The host passes the store's own methods, so the store that
 * accepts a GetMe identity is the same store that playback re-checks before each read. Every callback is an in-memory
 * update with no I/O, so the session may call it while it holds its lock.
 */
internal class TelegramNativeAccountPort(
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
 * What the session has verified about its Telegram account. It is kept apart from TDLib's authorization state: TDLib can
 * report Ready while the account is still unverified, and playback must wait for [Admitted], not for Ready.
 */
internal sealed interface TelegramNativeAdmission {
    /**
     * Nothing is verified: the session has not reached Ready, its check failed or was refused, or the session has ended.
     * Telegram playback is refused in this state.
     */
    data object NotAdmitted : TelegramNativeAdmission

    /** TDLib reported Ready and the GetMe check is in flight. Telegram playback is refused until the identity is accepted. */
    data object Verifying : TelegramNativeAdmission

    /** GetMe returned a positive user id, and the store accepted it for the current attempt. */
    data class Admitted(val account: AcceptedAccount) : TelegramNativeAdmission
}
