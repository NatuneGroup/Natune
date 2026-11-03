@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.shared.data.backup.IosRestoreFileOps
import moe.rukamori.archivetune.shared.data.backup.KtorDriveAppDataTransport
import moe.rukamori.archivetune.shared.data.platform.ios.IosProviderCredentials
import moe.rukamori.archivetune.shared.data.platform.ios.iosBackupNetworkAllowed
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.data.platform.ios.iosSecureRandomBytes
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveGateway
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataAuthorization
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataBackupException
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataBackupFailure
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataBackupMetadata
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataBackupOwner
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataFrequency
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataJobResult
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataLimits
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataNetwork
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataSchedule
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataScheduler
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataSettings
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataSettingsStore
import moe.rukamori.archivetune.shared.domain.backup.PreparedRestoreRegistry
import moe.rukamori.archivetune.shared.domain.lyrics.ai.Sha256
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthPort
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.SecureCredentialStore
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataBackupHost
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataFailure
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataSnapshot
import moe.rukamori.archivetune.shared.ui.settings.drivebackup.DriveAppDataAccountChooser
import moe.rukamori.archivetune.shared.ui.settings.drivebackup.DriveAppDataBackupBinding
import moe.rukamori.archivetune.shared.ui.settings.drivebackup.DriveAppDataBackupFormatter
import platform.Foundation.NSByteCountFormatter
import platform.Foundation.NSByteCountFormatterCountStyleFile
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem

/**
 * The iOS Drive app-data backup owners, the same wiring the desktop host mounts: the Google authorization over the
 * OS browser sheet, the in-process scheduler that stands in for a background job system, the private settings file
 * and the [DriveAppDataBackupHost] binding the backup page shows. The archive gateway and restore registry are
 * [IosBackupOwner]'s, so a Drive upload is the same portable archive a manual export writes and a cloud restore
 * lands on the same staged-restore path.
 *
 * The section is mounted whether or not the build carries an iOS OAuth client: without a client id the host
 * reports CONFIGURATION so the rows show the localized not-configured state instead of looking signed out, the
 * same surface the desktop page gives an unconfigured build. Scheduled uploads only run while the process lives —
 * iOS suspends rather than schedules — and the saved cadence is re-armed at open exactly as the desktop's
 * in-process scheduler does. Uploads honor the wifiOnly network rule: like the Android host's WorkManager
 * constraint, a due upload waits for a matching route rather than running on a metered connection.
 */
internal class IosDriveBackup(
    private val scope: CoroutineScope,
    http: HttpClient,
    credentials: SecureCredentialStore,
    browser: BrowserAuthPort,
    instanceDirectory: String,
    archives: BackupArchiveGateway,
    restores: PreparedRestoreRegistry,
    onRestartRequired: () -> Unit,
    private val report: (String) -> Unit,
    nowMillis: () -> Long = ::iosNowMillis,
) {
    private val scheduler = IosDriveScheduler(scope, report, nowMillis)
    private val authorization = IosGoogleDriveAuthorization(http, browser, credentials, report, nowMillis)
    private val owner = DriveAppDataBackupOwner(
        scope = scope,
        transport = KtorDriveAppDataTransport(http, DriveAppDataLimits.IOS_FILE_NAME),
        authorization = authorization,
        scheduler = scheduler,
        settingsStore = IosDriveSettingsFile("$instanceDirectory/$DRIVE_SETTINGS_FILE", report),
        archives = archives,
        restores = restores,
        nowMillis = nowMillis,
    )

    /** The page-facing binding over the owner: snapshot, chooser, restore preparation and failure records. */
    val binding = DriveAppDataBackupBinding(
        scope = scope,
        owner = owner,
        restores = restores,
        chooser = authorization,
        formatter = IosDriveFormatter,
        onRestartRequired = onRestartRequired,
    )

    private val configured = IosProviderCredentials.googleDriveClientId.isNotBlank()

    /**
     * The page's Drive host. Disconnecting also forgets and revokes the stored Google grant. While the build has
     * no iOS OAuth client id, its snapshot reports CONFIGURATION once the owner's state settles, so the page shows
     * the localized not-configured string as the Drive sign-in's unavailable state; choosing an account still
     * runs the chooser, which refuses with the same failure.
     */
    val drive: DriveAppDataBackupHost = object : DriveAppDataBackupHost by binding {
        override val snapshot: StateFlow<DriveAppDataSnapshot?> =
            if (configured) {
                binding.snapshot
            } else {
                binding.snapshot
                    .map { state -> state?.copy(failure = state.failure ?: DriveAppDataFailure.CONFIGURATION) }
                    .stateIn(scope, SharingStarted.Eagerly, binding.snapshot.value)
            }

        override suspend fun disconnect() {
            val account = owner.state.value.settings.accountName
            binding.disconnect()
            if (account != null) authorization.forget(account)
        }
    }

    /** Re-arms the saved schedule, which only lives as long as this process. */
    fun start() {
        scheduler.bind(owner)
        scope.launch {
            try {
                owner.load()
                scheduler.apply(owner.state.value.settings.schedule())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("drive backup schedule not restored: ${failure::class.simpleName}")
            }
        }
    }

    private companion object {
        const val DRIVE_SETTINGS_FILE = "drive-backup.json"
    }
}

/** The owner's schedule rule, repeated here because the owner applies it only after its own operations. */
private fun DriveAppDataSettings.schedule(): DriveAppDataSchedule =
    if (accountName == null || frequency == DriveAppDataFrequency.MANUAL) {
        DriveAppDataSchedule.Off
    } else {
        DriveAppDataSchedule.Periodic(
            days = frequency.days,
            network = if (wifiOnly) DriveAppDataNetwork.UNMETERED else DriveAppDataNetwork.CONNECTED,
        )
    }

/**
 * Google sign-in for Drive through the ASWebAuthenticationSession sheet and PKCE: the ephemeral browser loads
 * Google's consent page and the code returns on a custom URL scheme — the explicit `ArchiveTuneGoogleDriveUrlScheme`
 * when the build sets one, otherwise the OAuth client's reversed client id. The plist always registers the bundle
 * id, a scheme Google also accepts for iOS clients, and the session delivers any custom-scheme callback to the
 * waiting port regardless, so no build can produce an empty or unhandled redirect. The client is a public
 * installed-app client, so no secret is sent or stored. Only the refresh token is kept, in the Keychain vault;
 * access tokens stay in memory. Without a configured client id the chooser reports CONFIGURATION, which the page
 * shows as "not configured".
 */
internal class IosGoogleDriveAuthorization(
    private val http: HttpClient,
    private val browser: BrowserAuthPort,
    private val vault: SecureCredentialStore,
    private val report: (String) -> Unit,
    private val nowMillis: () -> Long,
) : DriveAppDataAuthorization, DriveAppDataAccountChooser {
    private class AccessToken(val account: String, val value: String, val expiresAtMillis: Long)

    private val lock = Mutex()
    private var cached: AccessToken? = null
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chooseAuthorizedAccount(): String? {
        val clientId = IosProviderCredentials.googleDriveClientId
        if (clientId.isBlank()) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.CONFIGURATION)
        }
        val scheme = IosProviderCredentials.googleDriveUrlScheme.ifBlank { reversedClientId(clientId) }
        val redirect = "$scheme:$CALLBACK_PATH"
        val verifier = secureUrlSafe(VERIFIER_BYTES)
        val challenge = URL_SAFE.encode(Sha256.digest(verifier.encodeToByteArray()))
        val state = secureUrlSafe(STATE_BYTES)
        val url = URLBuilder(AUTHORIZE_URL).apply {
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirect)
            parameters.append("response_type", "code")
            parameters.append("scope", "openid email ${DriveAppDataLimits.SCOPE}")
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("state", state)
            parameters.append("access_type", "offline")
            // Consent each time, so Google always returns a refresh token for the chosen account.
            parameters.append("prompt", "consent select_account")
        }.buildString()
        val result = try {
            browser.authorize(
                BrowserAuthRequest(
                    provider = "google_drive",
                    scope = CredentialScope.PERSONAL,
                    authorizationUrl = url,
                    redirectUri = redirect,
                    state = state,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (dismissed: IllegalStateException) {
            // A dismissed or unavailable sheet is a cancel, the same no-change answer a declined consent gives.
            return null
        } catch (rejected: IllegalArgumentException) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION, rejected)
        }
        val code = queryParam(result.callbackUri, "code") ?: return null
        val grant = exchange(
            clientId,
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to redirect,
        )
        val scopes = grant.string("scope").orEmpty().split(' ')
        if (DriveAppDataLimits.SCOPE !in scopes) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
        }
        val account = accountOf(grant.string("id_token"), clientId)
        val refresh = grant.string("refresh_token")?.takeIf { it.isNotBlank() }
            ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
        val access = grant.string("access_token")
            ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
        lock.withLock {
            try {
                vault.write(GRANT_KEY, buildJsonObject {
                    put("account", account)
                    put("refreshToken", refresh)
                }.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw DriveAppDataBackupException(DriveAppDataBackupFailure.STORAGE, failure)
            }
            cached = AccessToken(account, access, expiry(grant))
        }
        return account
    }

    override suspend fun token(accountName: String): String = lock.withLock {
        cached?.takeIf { it.account == accountName && it.expiresAtMillis > nowMillis() + EXPIRY_MARGIN_MS }?.let {
            return@withLock it.value
        }
        val clientId = IosProviderCredentials.googleDriveClientId
        if (clientId.isBlank()) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.CONFIGURATION)
        }
        val stored = storedGrant() ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
        if (stored.first != accountName) throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
        val grant = exchange(clientId, "grant_type" to "refresh_token", "refresh_token" to stored.second)
        val access = grant.string("access_token")
            ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
        cached = AccessToken(accountName, access, expiry(grant))
        access
    }

    override suspend fun invalidate(token: String) {
        lock.withLock { if (cached?.value == token) cached = null }
    }

    /** Drops the stored grant for [accountName] and asks Google to revoke it. A failed revoke is only logged. */
    suspend fun forget(accountName: String) {
        val refresh = lock.withLock {
            cached = null
            val stored = storedGrant()?.takeIf { it.first == accountName } ?: return@withLock null
            try {
                vault.remove(GRANT_KEY)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("drive grant not removed: ${failure::class.simpleName}")
            }
            stored.second
        } ?: return
        try {
            http.post(REVOKE_URL) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("token=${formEncode(refresh)}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("drive grant not revoked: ${failure::class.simpleName}")
        }
    }

    private suspend fun storedGrant(): Pair<String, String>? {
        val raw = try {
            vault.read(GRANT_KEY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.STORAGE, failure)
        } ?: return null
        val record = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val account = record.string("account") ?: return null
        val refresh = record.string("refreshToken") ?: return null
        return account to refresh
    }

    private suspend fun exchange(clientId: String, vararg fields: Pair<String, String>): JsonObject {
        val body = buildString {
            append("client_id=")
            append(formEncode(clientId))
            fields.forEach { (name, value) ->
                append('&')
                append(formEncode(name))
                append('=')
                append(formEncode(value))
            }
        }
        val response: HttpResponse = try {
            http.post(TOKEN_URL) {
                header(HttpHeaders.Accept, "application/json")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.NETWORK, failure)
        }
        val text = try {
            response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.NETWORK, failure)
        }
        if (!response.status.isSuccess()) {
            val failure = when {
                response.status.value >= 500 -> DriveAppDataBackupFailure.NETWORK
                // invalid_client means the configured client id is wrong for this flow.
                text.contains("invalid_client") -> DriveAppDataBackupFailure.CONFIGURATION
                else -> DriveAppDataBackupFailure.AUTHORIZATION
            }
            throw DriveAppDataBackupException(failure)
        }
        return try {
            json.parseToJsonElement(text) as JsonObject
        } catch (failure: Exception) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION, failure)
        }
    }

    /**
     * The account email from the ID token. The token came straight from Google's token endpoint over TLS, so its
     * claims are read without a signature check, as OpenID Connect allows for that channel; the audience must match.
     */
    private fun accountOf(idToken: String?, clientId: String): String {
        val claims = try {
            val payload = requireNotNull(idToken).split('.')[1]
            json.parseToJsonElement(URL_SAFE.decode(payload).decodeToString()) as JsonObject
        } catch (failure: Exception) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION, failure)
        }
        val email = claims.string("email")?.takeIf { it.isNotBlank() }
        val audience = claims.string("aud")
        val verified = (claims["email_verified"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (email == null || audience != clientId || !verified) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
        }
        return email
    }

    private fun expiry(grant: JsonObject): Long {
        val seconds = (grant["expires_in"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_EXPIRY_SECONDS
        return nowMillis() + seconds.coerceIn(60L, 24 * 3600L) * 1000L
    }

    private fun secureUrlSafe(bytes: Int): String =
        URL_SAFE.encode(iosSecureRandomBytes(bytes))

    /** One decoded query field of the callback URL, or null when it is absent. */
    private fun queryParam(url: String, name: String): String? =
        NSURLComponents.componentsWithString(url)?.queryItems
            ?.filterIsInstance<NSURLQueryItem>()
            ?.firstOrNull { it.name == name }
            ?.value

    /** The reversed client id of a Google iOS OAuth client: the dot-separated labels in reverse order. */
    private fun reversedClientId(clientId: String): String =
        clientId.split('.').asReversed().joinToString(".")

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val CALLBACK_PATH = "/oauthredirect"
        const val VERIFIER_BYTES = 48
        const val STATE_BYTES = 24
        const val EXPIRY_MARGIN_MS = 60_000L
        const val DEFAULT_EXPIRY_SECONDS = 3_600L

        /** URL-safe Base64 that decodes padded and unpadded input alike, so an unpadded JWT segment never fails. */
        val URL_SAFE = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        val GRANT_KEY = CredentialKey("google_drive", CredentialScope.PERSONAL, "ios_grant")
    }
}

/**
 * Runs the periodic and manual Drive uploads inside this process, in place of a job system the host does not have:
 * an upload only runs while the app is alive, and the saved cadence is re-armed at open. The periodic loop wakes at
 * least hourly and uploads once the newest backup is older than the chosen cadence; a manual request runs at once.
 * Transient network failures retry with a growing pause inside the owner's retry budget.
 *
 * Every run first waits for the network rule the request was made under — the periodic schedule's, or the manual
 * request's wifiOnly setting at enqueue — held like the Android host's WorkManager constraint: a due upload waits
 * on a matching route rather than running on a metered connection, and the wait is not a failed attempt.
 */
internal class IosDriveScheduler(
    private val scope: CoroutineScope,
    private val report: (String) -> Unit,
    private val nowMillis: () -> Long,
) : DriveAppDataScheduler {
    private val lock = Mutex()
    private var owner: DriveAppDataBackupOwner? = null
    private var periodic: Job? = null
    private var manual: Job? = null

    fun bind(owner: DriveAppDataBackupOwner) {
        this.owner = owner
    }

    override suspend fun apply(schedule: DriveAppDataSchedule) = lock.withLock {
        periodic?.cancel()
        periodic = (schedule as? DriveAppDataSchedule.Periodic)?.let { rule -> scope.launch { runPeriodic(rule) } }
    }

    override suspend fun enqueueNow(accountName: String) = lock.withLock {
        if (manual?.isActive == true) return@withLock
        // The manual request carries the wifiOnly rule current at enqueue, like the Android host baking the same
        // constraint into its one-time work request.
        val bound = owner ?: return@withLock
        val network = if (bound.state.value.settings.wifiOnly) {
            DriveAppDataNetwork.UNMETERED
        } else {
            DriveAppDataNetwork.CONNECTED
        }
        manual = scope.launch { runWithRetries(accountName, network) }
    }

    override suspend fun cancelAll() = lock.withLock {
        periodic?.cancel()
        manual?.cancel()
        periodic = null
        manual = null
    }

    private suspend fun runPeriodic(rule: DriveAppDataSchedule.Periodic) {
        val interval = rule.days.coerceAtLeast(1) * DAY_MS
        while (currentCoroutineContext().isActive) {
            val bound = owner ?: return
            val settings = bound.state.value.settings
            val account = settings.accountName ?: return
            val last = settings.backup?.modifiedAtMillis ?: 0L
            val wait = last + interval - nowMillis()
            if (wait > 0) {
                delay(minOf(wait, CHECK_INTERVAL_MS))
                continue
            }
            runWithRetries(account, rule.network)
            // A run that did not produce a newer backup waits for the next check instead of retrying at once.
            if ((bound.state.value.settings.backup?.modifiedAtMillis ?: 0L) <= last) delay(CHECK_INTERVAL_MS)
        }
    }

    private suspend fun runWithRetries(accountName: String, network: DriveAppDataNetwork) {
        val bound = owner ?: return
        var attempt = 0
        while (true) {
            if (!awaitUploadNetwork(accountName, network)) return
            when (val result = bound.runUpload(accountName, attempt)) {
                DriveAppDataJobResult.RetryLater -> {
                    attempt += 1
                    delay(RETRY_BASE_MS shl attempt.coerceAtMost(6))
                }
                is DriveAppDataJobResult.Failed -> {
                    report("drive backup failed: ${result.failure}")
                    return
                }
                DriveAppDataJobResult.Succeeded, DriveAppDataJobResult.Cancelled -> return
            }
        }
    }

    /**
     * Holds the run until the default route satisfies [network]: UNMETERED waits for a non-WWAN route — cellular
     * counts as metered — and CONNECTED waits for any online route. Returns false when the account changed
     * mid-wait or the job was cancelled, so a stale run never starts an upload.
     */
    private suspend fun awaitUploadNetwork(accountName: String, network: DriveAppDataNetwork): Boolean {
        while (currentCoroutineContext().isActive) {
            val bound = owner ?: return false
            if (bound.state.value.settings.accountName != accountName) return false
            if (iosBackupNetworkAllowed(unmeteredOnly = network == DriveAppDataNetwork.UNMETERED)) return true
            delay(NETWORK_WAIT_MS)
        }
        return false
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
        const val RETRY_BASE_MS = 15_000L
        const val NETWORK_WAIT_MS = 60_000L
    }
}

/**
 * The Drive preferences and last backup record, in a private file of the instance directory. A file that cannot be
 * read is logged and reads as the defaults, so one damaged file never locks the user out of Drive backup.
 */
internal class IosDriveSettingsFile(
    private val path: String,
    private val report: (String) -> Unit,
) : DriveAppDataSettingsStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val files = IosRestoreFileOps()

    override suspend fun read(): DriveAppDataSettings = withContext(Dispatchers.Default) {
        val bytes = try {
            files.readBytes(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("drive backup settings unreadable: ${failure::class.simpleName}")
            null
        } ?: return@withContext DriveAppDataSettings()
        try {
            val root = json.parseToJsonElement(bytes.decodeToString()) as JsonObject
            val backup = (root["backup"] as? JsonObject)?.let { saved ->
                DriveAppDataBackupMetadata(
                    id = requireNotNull(saved.text("id")),
                    modifiedAtMillis = requireNotNull(saved.number("modifiedAtMillis")),
                    sizeBytes = requireNotNull(saved.number("sizeBytes")),
                    md5Hex = requireNotNull(saved.text("md5Hex")),
                )
            }
            DriveAppDataSettings(
                accountName = root.text("accountName"),
                frequency = root.text("frequency")?.let { DriveAppDataFrequency.valueOf(it) }
                    ?: DriveAppDataFrequency.MANUAL,
                wifiOnly = (root["wifiOnly"] as? JsonPrimitive)?.booleanOrNull ?: true,
                backup = backup,
                lastFailure = root.text("lastFailure")?.let { DriveAppDataBackupFailure.valueOf(it) },
                lastFailureAtMillis = root.number("lastFailureAtMillis") ?: 0L,
            )
        } catch (failure: Exception) {
            report("drive backup settings unreadable: ${failure::class.simpleName}")
            DriveAppDataSettings()
        }
    }

    override suspend fun write(settings: DriveAppDataSettings) = withContext(Dispatchers.Default + NonCancellable) {
        val body = buildJsonObject {
            settings.accountName?.let { put("accountName", it) }
            put("frequency", settings.frequency.name)
            put("wifiOnly", settings.wifiOnly)
            settings.backup?.let { backup ->
                put("backup", buildJsonObject {
                    put("id", backup.id)
                    put("modifiedAtMillis", backup.modifiedAtMillis)
                    put("sizeBytes", backup.sizeBytes)
                    put("md5Hex", backup.md5Hex)
                })
            }
            settings.lastFailure?.let { put("lastFailure", it.name) }
            put("lastFailureAtMillis", settings.lastFailureAtMillis)
        }.toString()
        files.writeAtomically(path, body.encodeToByteArray())
        Unit
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
}

/** Dates and sizes in the default locale, as the Android host formats the newest Drive backup. */
internal object IosDriveFormatter : DriveAppDataBackupFormatter {
    private val dateFormat = NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterShortStyle
    }

    override fun date(epochMillis: Long): String =
        dateFormat.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))

    override fun size(bytes: Long): String =
        NSByteCountFormatter.stringFromByteCount(bytes, NSByteCountFormatterCountStyleFile)
}

/** Percent-encodes one form value over its UTF-8 bytes, keeping the RFC 3986 unreserved set. */
private fun formEncode(value: String): String {
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xff
        val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (unreserved) {
            out.append(c.toChar())
        } else {
            out.append('%').append(FORM_HEX[c shr 4]).append(FORM_HEX[c and 0x0f])
        }
    }
    return out.toString()
}

private const val FORM_HEX = "0123456789ABCDEF"
