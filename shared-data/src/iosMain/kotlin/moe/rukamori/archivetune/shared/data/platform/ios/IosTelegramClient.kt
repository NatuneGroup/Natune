@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CloseableCoroutineDispatcher
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
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import cnames.structs.at_telegram_client
import moe.rukamori.archivetune.nativetelegram.at_telegram_client_create
import moe.rukamori.archivetune.nativetelegram.at_telegram_client_destroy
import moe.rukamori.archivetune.nativetelegram.at_telegram_client_execute
import moe.rukamori.archivetune.nativetelegram.at_telegram_client_receive
import moe.rukamori.archivetune.nativetelegram.at_telegram_client_send
import moe.rukamori.archivetune.shared.domain.telegram.TelegramApiCredentials
import moe.rukamori.archivetune.shared.domain.telegram.TelegramAuthState
import moe.rukamori.archivetune.shared.domain.telegram.TelegramClientSession
import moe.rukamori.archivetune.shared.domain.telegram.TelegramCodeType
import platform.Foundation.*
import platform.UIKit.UIDevice

/**
 * The iOS [TelegramClientSession]: the single owner of one TDLib JSON client (ATNativeTelegram) for one Telegram login.
 * It mirrors the desktop TDLight session.
 *
 * [start] creates the client and [close] ends it, so a session never runs two TDLib instances over one database. One
 * dedicated thread receives every update and reply; requests carry an `@extra` id that routes the reply back to its
 * caller, and an `error` reply throws [IosTelegramRequestException].
 *
 * [authState] is TDLib's own authorization progress. [admission] is whether the account is verified: on Ready the
 * session asks TDLib for the logged-in user (getMe), and only a positive id that [nativeAccount] accepts for the newest
 * attempt admits it. Playback reads files only through [fileOperations], which answers only while the account is
 * admitted. Nothing persisted admits an account.
 *
 * [storageRoot] is the per-user application-support directory for the TDLib database and files. The database is not
 * encrypted at rest, as on Android and desktop. When the app was built without the TDLib library the native client
 * cannot be created, and the session reports [TelegramAuthState.Unsupported] instead of waiting forever.
 */
class IosTelegramClient(
    private val credentials: TelegramApiCredentials?,
    private val storageRoot: String,
    private val applicationVersion: String,
    private val cancelRetainedDownloads: suspend () -> Unit,
    private val nativeAccount: IosTelegramNativeAccountPort,
    private val log: (String) -> Unit,
) : TelegramClientSession {
    private val stateFlow = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Idle)
    override val authState: StateFlow<TelegramAuthState> = stateFlow.asStateFlow()

    private val admissionFlow = MutableStateFlow<IosTelegramNativeAdmission>(IosTelegramNativeAdmission.NotAdmitted)

    /** Whether a verified Telegram account stands behind this session. Playback and browse read this, not [authState]. */
    val admission: StateFlow<IosTelegramNativeAdmission> = admissionFlow.asStateFlow()

    // Every field below is guarded by [lock]. Sends and the final destroy also run under it, so a request can never reach
    // a native client that the receiver thread has already destroyed.
    private val lock = NSLock()
    private var client: CPointer<at_telegram_client>? = null
    private var closedSignal: CompletableDeferred<Unit>? = null
    private var closeRequested = false
    private val pending = HashMap<String, CompletableDeferred<JsonObject>>()
    private var nextRequestId = 0L

    // Every change of the admission context moves the epoch on, so a getMe answer asked for an earlier context changes
    // nothing. That stops a late reply from admitting an account that has been logged out, replaced or closed.
    private var admissionEpoch = 0L
    private var admissionJob: Job? = null

    /** Login requests and the getMe check. Cancelled on close. */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The receiver loop must outlive [workScope]: it is the one that destroys the native client after Closed. */
    private val receiverScope = CoroutineScope(SupervisorJob())

    /**
     * Creates the TDLib client when the session has credentials, is not closed and has no client yet. Returns true when
     * a client is running.
     */
    fun start(): Boolean = lock.guarded { startLocked() }

    private fun startLocked(): Boolean {
        if (closeRequested) return false
        if (client != null) return true
        if (credentials == null) {
            stateFlow.value = TelegramAuthState.Idle
            return false
        }
        // Error-level TDLib logging only. Neither the code, the password, the phone number nor the api_hash is logged.
        at_telegram_client_execute(null, """{"@type":"setLogVerbosityLevel","new_verbosity_level":1}""")
        val created = at_telegram_client_create()
        if (created == null) {
            log("the TDLib JSON client is unavailable in this build")
            stateFlow.value = TelegramAuthState.Unsupported("NativeLibraryMissing")
            return false
        }
        client = created
        closedSignal = CompletableDeferred()
        stateFlow.value = TelegramAuthState.Connecting
        val receiver = newSingleThreadContext("ArchiveTune Telegram receiver")
        receiverScope.launch(receiver) { receiveLoop(created, receiver) }
        // Any request makes TDLib publish its first authorization state; the version query has no other effect.
        at_telegram_client_send(created, """{"@type":"getOption","name":"version"}""")
        return true
    }

    override suspend fun submitPhoneNumber(phoneNumber: String) {
        // TDLib accepts a new number while it waits for a code, so a number submitted then restarts the code flow.
        request("setAuthenticationPhoneNumber", buildJsonObject {
            put("@type", "setAuthenticationPhoneNumber")
            put("phone_number", phoneNumber.trim())
        }, AUTH_REQUEST_TIMEOUT_MS)
    }

    override suspend fun submitCode(code: String) {
        request("checkAuthenticationCode", buildJsonObject {
            put("@type", "checkAuthenticationCode")
            put("code", code.trim())
        }, AUTH_REQUEST_TIMEOUT_MS)
    }

    override suspend fun submitPassword(password: String) {
        // The password is sent as typed: trimming would change a password that legitimately ends in a space.
        request("checkAuthenticationPassword", buildJsonObject {
            put("@type", "checkAuthenticationPassword")
            put("password", password)
        }, AUTH_REQUEST_TIMEOUT_MS)
    }

    override suspend fun resendCode() {
        request("resendAuthenticationCode", buildJsonObject {
            put("@type", "resendAuthenticationCode")
            putJsonObject("reason") { put("@type", "resendCodeReasonUserRequest") }
        }, AUTH_REQUEST_TIMEOUT_MS)
    }

    override suspend fun logOut() {
        // The verified account ends first, so no file read that starts during the logout is admitted.
        lock.guarded { endAdmissionLocked() }
        try {
            cancelRetainedDownloads()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("cancelling Telegram downloads before logout failed: ${failure::class.simpleName}")
        }
        // A failure is logged and never thrown, so the local cleanup that follows a logout always runs.
        try {
            request("logOut", buildJsonObject { put("@type", "logOut") }, AUTH_REQUEST_TIMEOUT_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("TDLib logOut failed: ${failure.message ?: failure::class.simpleName}")
        }
    }

    override suspend fun close() {
        val running: CPointer<at_telegram_client>?
        val signal: CompletableDeferred<Unit>?
        lock.lock()
        try {
            if (closeRequested) return
            closeRequested = true
            // The verified account ends before close, so no file read is admitted while TDLib flushes its databases.
            endAdmissionLocked()
            running = client
            signal = closedSignal
            if (running != null) at_telegram_client_send(running, """{"@type":"close"}""")
        } finally {
            lock.unlock()
        }
        workScope.cancel()
        if (running == null) {
            stateFlow.value = TelegramAuthState.Idle
            return
        }
        // TDLib reports authorizationStateClosed after its databases are flushed. The bound only stops a hung native
        // call from holding the caller forever.
        val confirmed = signal != null && withTimeoutOrNull(CLOSE_CONFIRM_TIMEOUT_MS) { signal.await() } != null
        if (!confirmed) log("TDLib did not confirm its close within $CLOSE_CONFIRM_TIMEOUT_MS ms")
        stateFlow.value = TelegramAuthState.Idle
    }

    /**
     * The file operations over the running client, for the playback port. Null unless the session is Ready and its account
     * is admitted, so a Telegram track never reads through a client that is still logging in, is unverified, or was closed.
     */
    internal fun fileOperations(): IosTelegramFileOperations? = lock.guarded {
        val admitted = admissionFlow.value is IosTelegramNativeAdmission.Admitted
        if (closeRequested || !admitted || client == null || stateFlow.value !is TelegramAuthState.Ready) {
            null
        } else {
            IosTelegramTdlibFileOperations(::request)
        }
    }

    /**
     * The t.me link of one channel message, resolved through the admitted TDLib session the same way
     * the Android `TdApi.GetMessageLink` call resolves it: TDLib builds the link for its opaque message
     * id, never the app. Null while no verified account runs or when TDLib cannot build the link.
     */
    suspend fun messageLink(chatId: Long, messageId: Long): String? {
        val admitted = lock.guarded {
            !closeRequested && client != null &&
                admissionFlow.value is IosTelegramNativeAdmission.Admitted &&
                stateFlow.value is TelegramAuthState.Ready
        }
        if (!admitted) return null
        return try {
            request(
                "getMessageLink",
                buildJsonObject {
                    put("@type", "getMessageLink")
                    put("chat_id", chatId)
                    put("message_id", messageId)
                },
                AUTH_REQUEST_TIMEOUT_MS,
            ).telegramText("link")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("getMessageLink failed: ${failure.message ?: failure::class.simpleName}")
            null
        }
    }

    /** Sends one request and returns its reply. A TDLib `error` reply throws, as does a missing reply within [timeoutMs]. */
    internal suspend fun request(operation: String, query: JsonObject, timeoutMs: Long): JsonObject {
        val answer = exchange(operation, query, timeoutMs)
        if (answer.telegramText("@type") == "error") {
            throw IosTelegramRequestException("$operation failed with TDLib error ${answer.telegramInt("code") ?: 0}")
        }
        return answer
    }

    private suspend fun exchange(operation: String, query: JsonObject, timeoutMs: Long): JsonObject {
        val reply = CompletableDeferred<JsonObject>()
        val id = lock.guarded {
            if (closeRequested) throw IosTelegramRequestException("$operation after the Telegram session was closed")
            val running = client ?: throw IosTelegramRequestException("$operation before the Telegram client started")
            val id = "at-${++nextRequestId}"
            pending[id] = reply
            val body = JsonObject(query + ("@extra" to JsonPrimitive(id))).toString()
            if (at_telegram_client_send(running, body) != 0) {
                pending.remove(id)
                throw IosTelegramRequestException("$operation could not be queued")
            }
            id
        }
        val answer = try {
            withTimeoutOrNull(timeoutMs) { reply.await() }
        } finally {
            lock.guarded { pending.remove(id) }
        }
        return answer ?: throw IosTelegramRequestException("$operation got no TDLib reply within $timeoutMs ms")
    }

    /**
     * The one receiving thread. It routes replies by `@extra`, handles authorization updates, and destroys the native
     * client after Closed, when nothing else can still be receiving from it.
     */
    private fun receiveLoop(running: CPointer<at_telegram_client>, receiver: CloseableCoroutineDispatcher) {
        try {
            while (true) {
                val raw = at_telegram_client_receive(running, RECEIVE_TIMEOUT_SECONDS)?.toKString()
                if (raw == null) {
                    if (lock.guarded { client !== running }) break
                    continue
                }
                val message = try {
                    Json.parseToJsonElement(raw) as? JsonObject
                } catch (_: Exception) {
                    null
                } ?: continue
                val extra = message.telegramText("@extra")
                if (extra != null) {
                    lock.guarded { pending.remove(extra) }?.complete(message)
                    continue
                }
                if (message.telegramText("@type") != "updateAuthorizationState") continue
                val state = message.telegramObject("authorization_state") ?: continue
                handleAuthorizationState(running, state)
                if (state.telegramText("@type") == "authorizationStateClosed") break
            }
        } finally {
            val waiting = lock.guarded {
                if (client === running) client = null
                at_telegram_client_destroy(running)
                pending.values.toList().also { pending.clear() }
            }
            waiting.forEach { it.completeExceptionally(IosTelegramRequestException("the Telegram client closed")) }
            // A single-thread dispatcher cannot close itself from its own thread.
            CoroutineScope(Dispatchers.Default).launch { receiver.close() }
        }
    }

    private fun handleAuthorizationState(running: CPointer<at_telegram_client>, state: JsonObject) {
        val type = state.telegramText("@type").orEmpty()
        // Any state other than Ready ends the verified account, so a state that is not Ready never leaves one admitted.
        if (type != "authorizationStateReady") lock.guarded { endAdmissionLocked() }
        when (type) {
            "authorizationStateWaitTdlibParameters" -> sendTdlibParameters()
            "authorizationStateWaitPhoneNumber" -> stateFlow.value = TelegramAuthState.WaitPhoneNumber
            "authorizationStateWaitCode" -> {
                val codeInfo = state.telegramObject("code_info")
                stateFlow.value = TelegramAuthState.WaitCode(
                    phoneNumber = codeInfo?.telegramText("phone_number").orEmpty(),
                    codeType = codeTypeOf(codeInfo?.telegramObject("type")?.telegramText("@type")),
                    canResend = codeInfo?.telegramObject("next_type") != null,
                    resendTimeoutSeconds = codeInfo?.telegramInt("timeout") ?: 0,
                )
            }
            "authorizationStateWaitPassword" ->
                stateFlow.value = TelegramAuthState.WaitPassword(state.telegramText("password_hint")?.takeIf(String::isNotBlank))
            "authorizationStateReady" -> onAuthorizationReady(running)
            "authorizationStateLoggingOut" -> stateFlow.value = TelegramAuthState.LoggingOut
            "authorizationStateClosed" -> {
                // Closed is the last update a client delivers. A later start() creates a new client, which is how a logout
                // followed by a new login works.
                val signal = lock.guarded {
                    if (client === running) client = null
                    closedSignal
                }
                stateFlow.value = TelegramAuthState.Idle
                signal?.complete(Unit)
            }
            else -> stateFlow.value = TelegramAuthState.Unsupported(type.ifEmpty { "UnknownAuthorizationState" })
        }
    }

    private fun onAuthorizationReady(running: CPointer<at_telegram_client>) {
        stateFlow.value = TelegramAuthState.Ready
        lock.guarded {
            // A Ready that arrives while the session is closing belongs to a client that is going away.
            if (client !== running || closeRequested) return
            beginAdmissionLocked(running)
        }
    }

    /** Starts the getMe check. The attempt is begun before getMe is sent, so a late accept is refused. Caller holds [lock]. */
    private fun beginAdmissionLocked(running: CPointer<at_telegram_client>) {
        endAdmissionLocked()
        val epoch = admissionEpoch
        val attempt = nativeAccount.beginAttempt()
        admissionFlow.value = IosTelegramNativeAdmission.Verifying
        admissionJob = workScope.launch { verifyAccount(running, epoch, attempt) }
    }

    /** Ends the verified account and ignores any reply still in flight. Caller holds [lock]. */
    private fun endAdmissionLocked() {
        admissionEpoch++
        admissionJob?.cancel()
        admissionJob = null
        admissionFlow.value = IosTelegramNativeAdmission.NotAdmitted
        nativeAccount.revokeSession()
    }

    private suspend fun verifyAccount(running: CPointer<at_telegram_client>, epoch: Long, attempt: Long) {
        val userId: Long? = try {
            val user = request("getMe", buildJsonObject { put("@type", "getMe") }, AUTH_REQUEST_TIMEOUT_MS)
            val id = user.telegramLong("id")
            if (user.telegramText("@type") != "user" || id == null || id <= 0L) {
                log("getMe did not return a Telegram user; the session stays unadmitted")
                null
            } else {
                id
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("Telegram account check failed: ${failure.message ?: failure::class.simpleName}")
            null
        }
        lock.guarded {
            if (epoch != admissionEpoch || client !== running || closeRequested) return
            val accepted = userId?.let { nativeAccount.acceptSession(attempt, it) }
            if (accepted != null) {
                admissionFlow.value = IosTelegramNativeAdmission.Admitted(accepted)
            } else {
                admissionFlow.value = IosTelegramNativeAdmission.NotAdmitted
                if (userId != null) log("Telegram account was not accepted: a newer attempt or a revocation superseded it")
            }
        }
    }

    private fun sendTdlibParameters() {
        val identity = credentials ?: return
        val parameters = buildJsonObject {
            put("@type", "setTdlibParameters")
            put("use_test_dc", false)
            put("database_directory", "$storageRoot/db")
            put("files_directory", "$storageRoot/files")
            // An empty key matches the Android and desktop sessions. The app container is the only protection.
            put("database_encryption_key", "")
            put("use_file_database", true)
            put("use_chat_info_database", true)
            put("use_message_database", true)
            put("use_secret_chats", false)
            put("api_id", identity.apiId)
            put("api_hash", identity.apiHash)
            put("system_language_code", NSLocale.currentLocale.languageCode.ifBlank { "en" })
            put("device_model", UIDevice.currentDevice.model.ifBlank { "iPhone" })
            put("system_version", UIDevice.currentDevice.systemVersion.ifBlank { "0" })
            put("application_version", applicationVersion)
        }
        workScope.launch {
            val reply = try {
                exchange("setTdlibParameters", parameters, AUTH_REQUEST_TIMEOUT_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("setTdlibParameters failed: ${failure.message ?: failure::class.simpleName}")
                return@launch
            }
            if (reply.telegramText("@type") == "error") {
                log("setTdlibParameters failed with TDLib error ${reply.telegramInt("code") ?: 0}")
                stateFlow.value = TelegramAuthState.Unsupported("InvalidApiCredentials")
            }
        }
    }

    private fun codeTypeOf(type: String?): TelegramCodeType = when (type) {
        "authenticationCodeTypeTelegramMessage" -> TelegramCodeType.TELEGRAM_APP
        "authenticationCodeTypeSms" -> TelegramCodeType.SMS
        "authenticationCodeTypeCall" -> TelegramCodeType.CALL
        else -> TelegramCodeType.OTHER
    }

    companion object {
        /** The identity the build configuration supplies, or null when either part is missing. */
        fun credentialsOrNull(apiId: Int, apiHash: String): TelegramApiCredentials? =
            if (apiId > 0 && apiHash.isNotBlank()) TelegramApiCredentials(apiId, apiHash) else null

        /** Login steps wait on Telegram's servers; the getMe check uses the same bound. */
        private const val AUTH_REQUEST_TIMEOUT_MS = 30_000L
        private const val CLOSE_CONFIRM_TIMEOUT_MS = 10_000L

        /** Short enough that the receiver notices a destroyed client promptly, long enough not to spin. */
        private const val RECEIVE_TIMEOUT_SECONDS = 1.0
    }
}

internal fun JsonObject.telegramText(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.telegramObject(key: String): JsonObject? = this[key] as? JsonObject

/** TDLib writes int64 values as JSON strings and int53 values as numbers, so both forms are read. */
internal fun JsonObject.telegramLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

internal fun JsonObject.telegramInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.telegramFlag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private inline fun <T> NSLock.guarded(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
