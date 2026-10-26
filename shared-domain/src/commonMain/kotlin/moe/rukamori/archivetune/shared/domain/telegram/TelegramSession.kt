/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.telegram

import kotlinx.coroutines.flow.StateFlow

/**
 * The Telegram application identity that TDLib requires (api_id and api_hash). The host takes both from its build
 * configuration and never from the user. A zero id or a blank hash is refused here, so no client is ever started with
 * an empty identity, and the hash stays out of logs and diagnostics.
 */
data class TelegramApiCredentials(
    val apiId: Int,
    val apiHash: String,
) {
    init {
        require(apiId > 0) { "a Telegram api_id is positive" }
        require(apiHash.isNotBlank()) { "a Telegram api_hash must not be blank" }
    }

    override fun toString(): String = "TelegramApiCredentials(apiId=$apiId, apiHash=REDACTED)"
}

/** Where the current login code was delivered, for user-facing copy. Mirrors the retained `TelegramCodeType`. */
enum class TelegramCodeType {
    TELEGRAM_APP,
    SMS,
    CALL,
    OTHER,
}

/**
 * Authorization progress of a Telegram session, driven by TDLib's authorization states. Mirrors the retained
 * `TelegramAuthState` variant for variant, so the settings flow keeps its existing meaning.
 */
sealed interface TelegramAuthState {
    /** No client is running: the credentials are missing or refused, or the session was closed. */
    data object Idle : TelegramAuthState

    /** The client exists, and TDLib has not reported its first authorization state yet. */
    data object Connecting : TelegramAuthState

    data object WaitPhoneNumber : TelegramAuthState

    data class WaitCode(
        val phoneNumber: String,
        /** Where the current code was delivered: the Telegram app, SMS or a phone call. */
        val codeType: TelegramCodeType,
        /** True when TDLib offers a resend path. */
        val canResend: Boolean,
        /** Seconds the user must wait before a resend is accepted. */
        val resendTimeoutSeconds: Int,
    ) : TelegramAuthState

    data class WaitPassword(
        val passwordHint: String?,
    ) : TelegramAuthState

    data object Ready : TelegramAuthState

    data object LoggingOut : TelegramAuthState

    /** An authorization state this integration does not implement, such as QR login or registration. */
    data class Unsupported(
        val stateName: String,
    ) : TelegramAuthState
}

/**
 * One Telegram login session, the portable contract that a host implements over its TDLib client.
 *
 * The host creates the TDLib client, supplies [TelegramApiCredentials] from its build configuration, and closes the
 * client when the session ends. Portable owners never create a client. A session that has no credentials, or has been
 * closed, reports [TelegramAuthState.Idle] and accepts no request.
 *
 * Progress is observed only through [authState]. A request that TDLib refuses throws, and the caller shows that failure
 * while [authState] stays the single source of truth for where the login stands. A code, password or phone number is
 * never logged or copied into a diagnostic.
 */
interface TelegramClientSession {
    val authState: StateFlow<TelegramAuthState>

    /** Starts the code flow for [phoneNumber]. Passing a new number while waiting for a code restarts the flow. */
    suspend fun submitPhoneNumber(phoneNumber: String)

    suspend fun submitCode(code: String)

    suspend fun submitPassword(password: String)

    /** Asks TDLib for a new code. Callers gate this on [TelegramAuthState.WaitCode.canResend] and its countdown. */
    suspend fun resendCode()

    /**
     * Stops the client and removes the on-device session. A failure is reported to the host's log and never thrown, so
     * a logout always finishes the local cleanup that follows it.
     */
    suspend fun logOut()

    /** Closes the TDLib client. The session accepts no request afterwards. */
    suspend fun close()
}
