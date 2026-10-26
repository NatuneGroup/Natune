/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.playback.TdlibFileOperations
import moe.rukamori.archivetune.desktop.playback.TdlightFileOperations
import moe.rukamori.archivetune.desktop.playback.TdlightNativeLibrary
import moe.rukamori.archivetune.desktop.playback.TdlightRequestException
import moe.rukamori.archivetune.desktop.playback.requestOk
import moe.rukamori.archivetune.shared.domain.telegram.TelegramApiCredentials
import moe.rukamori.archivetune.shared.domain.telegram.TelegramAuthState
import moe.rukamori.archivetune.shared.domain.telegram.TelegramClientSession
import moe.rukamori.archivetune.shared.domain.telegram.TelegramCodeType
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The desktop [TelegramClientSession]: the single owner of one TDLight client for one Telegram login.
 *
 * [start] creates the client and [close] ends it. Nothing else creates a client, so a session never runs two TDLib
 * instances over one database.
 *
 * Two facts are kept apart. [authState] is TDLib's own authorization progress, so it reads Ready as soon as TDLib does.
 * [admission] is whether a Telegram account is verified. On Ready the session asks TDLib who the logged-in user is
 * (GetMe) through the same client, and only a positive user id that [nativeAccount] accepts for the newest attempt admits
 * the account. Playback reads Telegram files through [fileOperations], which hands out this client only while the account
 * is admitted, so a track never reads through a client that is still logging in, is unverified, or has been closed.
 * Nothing persisted admits an account: a new process stays unadmitted until TDLib's GetMe answers again.
 *
 * [credentials] come from the build configuration; build them with [credentialsOrNull]. Without an identity the session
 * stays Idle and starts no client. The TDLib database is not encrypted at rest, as on Android, so it is kept only under
 * the per-user application data directory [storageRoot].
 *
 * [cancelRetainedDownloads] is the playback port's cancellation of the transfers it keeps past playback. [logOut] runs
 * it before the TDLib logout, because a transfer that outlives the session would keep writing into a wiped database.
 *
 * [nativeAccount] is the native Telegram entry points of the one accepted-account store, supplied by the host. The
 * session creates no account owner of its own, and every admission it holds is revoked on logout, closure or a session
 * end.
 */
internal class TdlightClientSession(
    private val credentials: TelegramApiCredentials?,
    private val storageRoot: File,
    private val applicationVersion: String,
    private val cancelRetainedDownloads: suspend () -> Unit,
    private val nativeAccount: TelegramNativeAccountPort,
    private val log: (String) -> Unit,
) : TelegramClientSession {
    private val stateFlow = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Idle)
    override val authState: StateFlow<TelegramAuthState> = stateFlow.asStateFlow()

    private val admissionFlow = MutableStateFlow<TelegramNativeAdmission>(TelegramNativeAdmission.NotAdmitted)

    /** Whether a verified Telegram account stands behind this session. Playback and browse read this, not [authState]. */
    val admission: StateFlow<TelegramNativeAdmission> = admissionFlow.asStateFlow()

    // TDLib calls the update handler on its own receiver thread, so every read and write of the client reference goes
    // through this lock. start() publishes the client before it releases the lock, so an update that arrives at once
    // still finds it.
    private val lock = Any()
    private var client: Client? = null
    private var closedSignal: CompletableDeferred<Unit>? = null
    private val closeRequested = AtomicBoolean(false)

    // Guarded by [lock]. Every change of the admission context moves [admissionEpoch] on, so a GetMe answer that was asked
    // for an earlier context finds a different epoch and changes nothing. That is what stops a late reply from admitting
    // an account that has been logged out, replaced or closed.
    private var admissionEpoch = 0L
    private var admissionJob: Job? = null

    // The GetMe check runs here because a request needs a coroutine for its reply timeout. It is cancelled on close.
    private val admissionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Creates the TDLib client when the session has credentials, is not closed and has no client yet. Returns true when
     * a client is running. A host without the native library gets [TelegramAuthState.Unsupported] rather than a state
     * that waits forever, so the settings flow can say that Telegram is unavailable on this machine.
     */
    fun start(): Boolean = synchronized(lock) { startLocked() }

    private fun startLocked(): Boolean {
        if (closeRequested.get()) return false
        if (client != null) return true
        if (credentials == null) {
            stateFlow.value = TelegramAuthState.Idle
            return false
        }
        try {
            TdlightNativeLibrary.ensureLoaded()
        } catch (failure: LinkageError) {
            log("TDLight native library is unavailable: ${failure.javaClass.simpleName}")
            stateFlow.value = TelegramAuthState.Unsupported("NativeLibraryMissing")
            return false
        }
        // Error-level TDLib logging only. Neither the code, the password, the phone number nor the api_hash is logged.
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        closedSignal = CompletableDeferred<Unit>()
        stateFlow.value = TelegramAuthState.Connecting
        client = Client.create(
            { update -> onUpdate(update) },
            { failure -> log("TDLib update handler failed: ${failure.javaClass.simpleName}") },
            { failure -> log("TDLib request failed: ${failure.javaClass.simpleName}") },
        )
        return true
    }

    override suspend fun submitPhoneNumber(phoneNumber: String) {
        // TDLib accepts a new number while it waits for a code, so a number submitted then restarts the code flow.
        authRequest("SetAuthenticationPhoneNumber", TdApi.SetAuthenticationPhoneNumber(phoneNumber.trim(), null))
    }

    override suspend fun submitCode(code: String) {
        authRequest("CheckAuthenticationCode", TdApi.CheckAuthenticationCode(code.trim()))
    }

    override suspend fun submitPassword(password: String) {
        // The password is sent as typed: trimming would change a password that legitimately ends in a space.
        authRequest("CheckAuthenticationPassword", TdApi.CheckAuthenticationPassword(password))
    }

    override suspend fun resendCode() {
        authRequest("ResendAuthenticationCode", TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest()))
    }

    override suspend fun logOut() {
        // The verified account ends first, so no file read that starts during the logout is admitted.
        synchronized(lock) { endAdmissionLocked() }
        try {
            cancelRetainedDownloads()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log("cancelling Telegram downloads before logout failed: ${failure.javaClass.simpleName}")
        }
        // A failure is logged and never thrown, so the local cleanup that follows a logout always runs.
        try {
            authRequest("LogOut", TdApi.LogOut())
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log("TDLib LogOut failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    override suspend fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        val (running, signal) = synchronized(lock) {
            // The verified account ends before TdApi.Close, so no file read is admitted while TDLib flushes its databases.
            endAdmissionLocked()
            client to closedSignal
        }
        admissionScope.cancel()
        if (running == null) {
            stateFlow.value = TelegramAuthState.Idle
            return
        }
        // TDLib asks the caller to wait for AuthorizationStateClosed, which is sent after its databases are flushed. The
        // bound only stops a hung native call from holding the caller forever.
        running.send(TdApi.Close(), null, null)
        val confirmed = signal != null && withTimeoutOrNull(CLOSE_CONFIRM_TIMEOUT_MS) { signal.await() } != null
        if (!confirmed) log("TDLib did not confirm its close within $CLOSE_CONFIRM_TIMEOUT_MS ms")
        stateFlow.value = TelegramAuthState.Idle
    }

    /**
     * The file operations over the running client, for the playback port. Null unless the session is Ready and its account
     * is admitted, so a Telegram track never reads through a client that is still logging in, is unverified, or was closed.
     */
    fun fileOperations(): TdlibFileOperations? = synchronized(lock) {
        val running = client
        val admitted = admissionFlow.value is TelegramNativeAdmission.Admitted
        if (closeRequested.get() || !admitted || running == null || stateFlow.value !is TelegramAuthState.Ready) {
            null
        } else {
            TdlightFileOperations(running)
        }
    }

    private fun onUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateAuthorizationState) handleAuthorizationState(update.authorizationState)
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        // Any state other than Ready ends the verified account. Logout, closure and a login that follows either all pass
        // through here, so a state that is not Ready never leaves an earlier account admitted.
        if (state !is TdApi.AuthorizationStateReady) synchronized(lock) { endAdmissionLocked() }
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendTdlibParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> stateFlow.value = TelegramAuthState.WaitPhoneNumber
            is TdApi.AuthorizationStateWaitCode -> {
                val codeInfo = state.codeInfo
                stateFlow.value = TelegramAuthState.WaitCode(
                    phoneNumber = codeInfo?.phoneNumber.orEmpty(),
                    codeType = codeTypeOf(codeInfo?.type),
                    canResend = codeInfo?.nextType != null,
                    resendTimeoutSeconds = codeInfo?.timeout ?: 0,
                )
            }
            is TdApi.AuthorizationStateWaitPassword ->
                stateFlow.value = TelegramAuthState.WaitPassword(state.passwordHint?.takeIf(String::isNotBlank))
            is TdApi.AuthorizationStateReady -> onAuthorizationReady()
            is TdApi.AuthorizationStateLoggingOut -> stateFlow.value = TelegramAuthState.LoggingOut
            is TdApi.AuthorizationStateClosed -> {
                // Closed is the last update a client delivers. A later start() creates a new client, which is how a
                // logout followed by a new login works.
                val signal = synchronized(lock) {
                    client = null
                    closedSignal
                }
                stateFlow.value = TelegramAuthState.Idle
                signal?.complete(Unit)
            }
            else -> stateFlow.value = TelegramAuthState.Unsupported(state.javaClass.simpleName)
        }
    }

    private fun onAuthorizationReady() {
        // TDLib's own Ready is reported as it is. Whether the account behind it may play is decided by the GetMe check.
        stateFlow.value = TelegramAuthState.Ready
        synchronized(lock) {
            val running = client
            // A Ready that arrives while the session is closing belongs to a client that is going away, so it starts no check.
            if (running == null || closeRequested.get()) return
            beginAdmissionLocked(running)
        }
    }

    /**
     * Starts the GetMe check for the Ready session on [running]. The attempt is begun before GetMe is sent, so the store
     * refuses an accept that finishes after a newer attempt or a revocation. Caller holds [lock].
     */
    private fun beginAdmissionLocked(running: Client) {
        // A Ready is a new session, so whatever the store still holds is revoked first. An identity admitted before this
        // point must not stay readable while the current one is still being verified.
        endAdmissionLocked()
        val epoch = admissionEpoch
        val attempt = nativeAccount.beginAttempt()
        admissionFlow.value = TelegramNativeAdmission.Verifying
        admissionJob = admissionScope.launch { verifyAccount(running, epoch, attempt) }
    }

    /**
     * Ends the verified account. The store forgets it, the check in flight is cancelled, and any reply that still arrives
     * is ignored because the epoch has moved on. Caller holds [lock].
     */
    private fun endAdmissionLocked() {
        admissionEpoch++
        admissionJob?.cancel()
        admissionJob = null
        admissionFlow.value = TelegramNativeAdmission.NotAdmitted
        nativeAccount.revokeSession()
    }

    /**
     * Asks TDLib for the logged-in user through the session's own client, and accepts that identity only while the same
     * context is still current. A TDLib error, a missing or non-positive user id, or no reply within the request timeout
     * leaves the account unadmitted. The session still reports Ready, because TDLib does.
     */
    private suspend fun verifyAccount(running: Client, epoch: Long, attempt: Long) {
        val userId: Long? = try {
            val user = running.requestOk("GetMe", TdApi.GetMe(), AUTH_REQUEST_TIMEOUT_MS) as? TdApi.User
            if (user == null || user.id <= 0L) {
                log("GetMe did not return a Telegram user; the session stays unadmitted")
                null
            } else {
                user.id
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log("Telegram account check failed: ${failure.message ?: failure.javaClass.simpleName}")
            null
        }
        synchronized(lock) {
            // A reply for a context that has since ended changes nothing, so a late answer cannot admit a stale account.
            if (epoch != admissionEpoch || client !== running || closeRequested.get()) return
            val accepted = userId?.let { nativeAccount.acceptSession(attempt, it) }
            if (accepted != null) {
                admissionFlow.value = TelegramNativeAdmission.Admitted(accepted)
            } else {
                admissionFlow.value = TelegramNativeAdmission.NotAdmitted
                if (userId != null) log("Telegram account was not accepted: a newer attempt or a revocation superseded it")
            }
        }
    }

    private fun codeTypeOf(type: TdApi.AuthenticationCodeType?): TelegramCodeType =
        when (type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> TelegramCodeType.TELEGRAM_APP
            is TdApi.AuthenticationCodeTypeSms -> TelegramCodeType.SMS
            is TdApi.AuthenticationCodeTypeCall -> TelegramCodeType.CALL
            else -> TelegramCodeType.OTHER
        }

    private fun sendTdlibParameters() {
        val identity = credentials ?: return
        val running = synchronized(lock) { client } ?: return
        val parameters = TdApi.SetTdlibParameters(
            /* useTestDc = */ false,
            /* databaseDirectory = */ File(storageRoot, "db").absolutePath,
            /* filesDirectory = */ File(storageRoot, "files").absolutePath,
            // An empty key matches the Android session. The per-user directory above is the only protection.
            /* databaseEncryptionKey = */ ByteArray(0),
            /* useFileDatabase = */ true,
            /* useChatInfoDatabase = */ true,
            /* useMessageDatabase = */ true,
            /* useSecretChats = */ false,
            /* apiId = */ identity.apiId,
            /* apiHash = */ identity.apiHash,
            /* systemLanguageCode = */ Locale.getDefault().language.ifBlank { "en" },
            /* deviceModel = */ System.getProperty("os.name").orEmpty().ifBlank { "Desktop" },
            /* systemVersion = */ System.getProperty("os.version").orEmpty().ifBlank { "0" },
            /* applicationVersion = */ applicationVersion,
        )
        running.send(
            parameters,
            Client.ResultHandler { result ->
                if (result is TdApi.Error) {
                    log("SetTdlibParameters failed with TDLib error ${result.code}")
                    stateFlow.value = TelegramAuthState.Unsupported("InvalidApiCredentials")
                }
            },
            Client.ExceptionHandler { failure -> log("SetTdlibParameters failed: ${failure.javaClass.simpleName}") },
        )
    }

    private fun requireRunning(operation: String): Client {
        if (closeRequested.get()) throw TdlightRequestException("$operation after the Telegram session was closed")
        return synchronized(lock) { client }
            ?: throw TdlightRequestException("$operation before the Telegram client started")
    }

    private suspend fun authRequest(operation: String, query: TdApi.Function<*>) {
        requireRunning(operation).requestOk(operation, query, AUTH_REQUEST_TIMEOUT_MS)
    }

    companion object {
        /**
         * The identity the build configuration supplies, or null when either part is missing. A null identity keeps the
         * session Idle instead of letting TDLib fail later with an empty api_id or api_hash.
         */
        fun credentialsOrNull(apiId: Int, apiHash: String): TelegramApiCredentials? =
            if (apiId > 0 && apiHash.isNotBlank()) TelegramApiCredentials(apiId, apiHash) else null

        /**
         * Login steps wait on Telegram's servers, and a password check takes noticeably longer than a code. The GetMe check
         * uses the same bound, so an unanswered check cannot hold the account in Verifying forever.
         */
        private const val AUTH_REQUEST_TIMEOUT_MS = 30_000L

        private const val CLOSE_CONFIRM_TIMEOUT_MS = 10_000L
    }
}
