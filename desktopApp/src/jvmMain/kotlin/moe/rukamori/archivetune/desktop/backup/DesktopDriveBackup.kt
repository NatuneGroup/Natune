package moe.rukamori.archivetune.desktop.backup

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Base64
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.data.backup.KtorDriveAppDataTransport
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
import moe.rukamori.archivetune.shared.domain.backup.InMemoryPreparedRestoreRegistry
import moe.rukamori.archivetune.shared.domain.backup.PreparedRestoreRegistry
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.SecureCredentialStore
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataBackupHost
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import moe.rukamori.archivetune.shared.ui.settings.drivebackup.DriveAppDataAccountChooser
import moe.rukamori.archivetune.shared.ui.settings.drivebackup.DriveAppDataBackupBinding
import moe.rukamori.archivetune.shared.ui.settings.drivebackup.DriveAppDataBackupFormatter

/** The desktop OAuth client ("Desktop app" type) of the Google Cloud project that owns the Drive app data folder. */
internal class GoogleDesktopClient(val id: String, val secret: String)

/**
 * The backup owners of the desktop process: the archive gateway, the one prepared-restore registry that manual files
 * and Drive downloads share, and the Google Drive app data owner with its desktop authorization, schedule and store.
 * A restore is staged and applied at the next start, so [restartRequested] asks the window for a restart.
 */
internal class DesktopBackup(
    private val scope: CoroutineScope,
    http: HttpClient,
    settings: VaultSettingsStore,
    snapshotDatabase: suspend (Path) -> Unit,
    instanceDirectory: Path,
    googleClient: () -> GoogleDesktopClient?,
    vault: SecureCredentialStore,
    openBrowser: (String) -> Boolean,
    private val report: (String) -> Unit,
) {
    val archives = DesktopBackupArchiveGateway(settings, snapshotDatabase, instanceDirectory)
    val restores: PreparedRestoreRegistry = InMemoryPreparedRestoreRegistry(archives)
    private val authorization = DesktopGoogleDriveAuthorization(http, googleClient, vault, openBrowser, report)
    private val scheduler = DesktopDriveScheduler(scope, report)
    private val owner = DriveAppDataBackupOwner(
        scope = scope,
        transport = KtorDriveAppDataTransport(http, DriveAppDataLimits.DESKTOP_FILE_NAME),
        authorization = authorization,
        scheduler = scheduler,
        settingsStore = DesktopDriveSettingsFile(instanceDirectory.resolve(DRIVE_SETTINGS_FILE), report),
        archives = archives,
        restores = restores,
    )
    private val restartState = MutableStateFlow(false)

    /** True after a restore was staged; the window offers to restart until the user answers. */
    val restartRequested: StateFlow<Boolean> = restartState.asStateFlow()

    val binding = DriveAppDataBackupBinding(
        scope = scope,
        owner = owner,
        restores = restores,
        chooser = authorization,
        formatter = DesktopDriveFormatter,
        onRestartRequired = { restartState.value = true },
    )

    /** The page's Drive host. Disconnecting also forgets and revokes the stored Google grant. */
    val drive: DriveAppDataBackupHost = object : DriveAppDataBackupHost by binding {
        override suspend fun disconnect() {
            val account = owner.state.value.settings.accountName
            binding.disconnect()
            if (account != null) authorization.forget(account)
        }
    }

    fun dismissRestart() {
        restartState.value = false
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
                report("drive backup schedule not restored: ${failure.javaClass.simpleName}")
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
 * Google sign-in for Drive through the installed-app loopback flow: the system browser opens Google's consent page,
 * Google redirects to a one-shot listener on 127.0.0.1, and the code is exchanged with PKCE. Only the refresh token is
 * kept, in the OS vault; access tokens stay in memory. Without a configured client the chooser reports CONFIGURATION,
 * which the page shows as "not configured".
 *
 * The JVM cannot tell a metered connection apart, so the Wi-Fi-only rule is stored and shown but every connection
 * counts as unmetered here.
 */
internal class DesktopGoogleDriveAuthorization(
    private val http: HttpClient,
    private val client: () -> GoogleDesktopClient?,
    private val vault: SecureCredentialStore,
    private val openBrowser: (String) -> Boolean,
    private val report: (String) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DriveAppDataAuthorization, DriveAppDataAccountChooser {
    private class AccessToken(val account: String, val value: String, val expiresAtMillis: Long)

    private val lock = Mutex()
    private var cached: AccessToken? = null
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chooseAuthorizedAccount(): String? {
        val configured = client() ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.CONFIGURATION)
        val verifier = randomToken(48)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomToken(24)
        val server = withContext(Dispatchers.IO) { ServerSocket(0, 4, InetAddress.getByName(LOOPBACK_HOST)) }
        try {
            val redirect = "http://$LOOPBACK_HOST:${server.localPort}"
            val url = URLBuilder(AUTHORIZE_URL).apply {
                parameters.append("client_id", configured.id)
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
            if (!openBrowser(url)) throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
            val code = awaitCallback(server, state) ?: return null
            val grant = exchange(
                configured,
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirect,
            )
            val scopes = grant.string("scope").orEmpty().split(' ')
            if (DriveAppDataLimits.SCOPE !in scopes) throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
            val account = accountOf(grant.string("id_token"), configured.id)
            val refresh = grant.string("refresh_token")?.takeIf { it.isNotBlank() }
                ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
            val access = grant.string("access_token") ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
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
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { server.close() }
        }
    }

    override suspend fun token(accountName: String): String = lock.withLock {
        cached?.takeIf { it.account == accountName && it.expiresAtMillis > nowMillis() + EXPIRY_MARGIN_MS }?.let {
            return@withLock it.value
        }
        val configured = client() ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.CONFIGURATION)
        val stored = storedGrant() ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
        if (stored.first != accountName) throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
        val grant = exchange(configured, "grant_type" to "refresh_token", "refresh_token" to stored.second)
        val access = grant.string("access_token") ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.AUTHORIZATION)
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
                report("drive grant not removed: ${failure.javaClass.simpleName}")
            }
            stored.second
        } ?: return
        try {
            http.submitForm(REVOKE_URL, parameters { append("token", refresh) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("drive grant not revoked: ${failure.javaClass.simpleName}")
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

    private suspend fun exchange(configured: GoogleDesktopClient, vararg fields: Pair<String, String>): JsonObject {
        val response: HttpResponse = try {
            http.submitForm(TOKEN_URL, parameters {
                append("client_id", configured.id)
                if (configured.secret.isNotBlank()) append("client_secret", configured.secret)
                fields.forEach { (name, value) -> append(name, value) }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.NETWORK, failure)
        }
        val body = try {
            response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.NETWORK, failure)
        }
        if (!response.status.isSuccess()) {
            val failure = when {
                response.status.value >= 500 -> DriveAppDataBackupFailure.NETWORK
                // invalid_client means the configured client id or secret is wrong for this flow.
                body.contains("invalid_client") -> DriveAppDataBackupFailure.CONFIGURATION
                else -> DriveAppDataBackupFailure.AUTHORIZATION
            }
            throw DriveAppDataBackupException(failure)
        }
        return try {
            json.parseToJsonElement(body) as JsonObject
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
            json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).decodeToString()) as JsonObject
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

    /**
     * Waits for Google's redirect with the matching [state]. Returns the code, or null when the user declined or the
     * wait ran out. Requests for other paths (a browser's favicon) are answered and ignored.
     */
    private suspend fun awaitCallback(server: ServerSocket, state: String): String? = withContext(Dispatchers.IO) {
        server.soTimeout = ACCEPT_POLL_MS
        val deadline = nowMillis() + CALLBACK_TIMEOUT_MS
        while (nowMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            val outcome = socket.use { answer(it, state) }
            if (outcome != null) return@withContext outcome.getOrNull()
        }
        null
    }

    /** Null when this request was not the callback; otherwise success with the code, or failure for a refusal. */
    private fun answer(socket: Socket, state: String): Result<String?>? {
        socket.soTimeout = READ_TIMEOUT_MS
        val line = try {
            readRequestLine(socket)
        } catch (_: IOException) {
            return null
        } ?: return null
        val target = line.split(' ').getOrNull(1) ?: return null
        val query = target.substringAfter('?', "")
        val fields = query.split('&').filter { it.isNotEmpty() }.associate { part ->
            URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
        }
        if (!target.startsWith("/?") && target != "/" || fields["state"] != state) {
            respond(socket, 404, null)
            return null
        }
        val code = fields["code"]?.takeIf { it.isNotBlank() }
        respond(socket, 200, DesktopStrings[if (code != null) "drive_auth_done" else "drive_auth_declined"])
        return if (code != null) Result.success(code) else Result.success(null)
    }

    private fun readRequestLine(socket: Socket): String? {
        val input = socket.getInputStream()
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < MAX_REQUEST_LINE) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) return bytes.toString(Charsets.US_ASCII).trimEnd('\r')
            bytes.write(next)
        }
        return null
    }

    private fun respond(socket: Socket, status: Int, message: String?) {
        val body = if (message == null) "" else {
            val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            "<!doctype html><html><head><meta charset=\"utf-8\"><title>${DesktopStrings["app_name"]}</title></head>" +
                "<body style=\"font-family:sans-serif;margin:3em\"><p>$escaped</p></body></html>"
        }
        val payload = body.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Not Found"
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${payload.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        try {
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(payload)
                flush()
            }
        } catch (_: IOException) {
            // The browser closed the tab; the code it already delivered still counts.
        }
    }

    private fun randomToken(bytes: Int): String = base64Url(ByteArray(bytes).also(random::nextBytes))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val ACCEPT_POLL_MS = 500
        const val READ_TIMEOUT_MS = 5_000
        const val CALLBACK_TIMEOUT_MS = 5 * 60_000L
        const val MAX_REQUEST_LINE = 8 * 1024
        const val EXPIRY_MARGIN_MS = 60_000L
        const val DEFAULT_EXPIRY_SECONDS = 3_600L
        val GRANT_KEY = CredentialKey("google_drive", CredentialScope.PERSONAL, "desktop_grant")
    }
}

/**
 * Runs the periodic and manual Drive uploads inside this process, in place of the Android job system. The periodic
 * loop wakes at least hourly and uploads once the newest backup is older than the chosen cadence; a manual request
 * runs at once. Transient network failures retry with a growing pause inside the owner's retry budget.
 */
internal class DesktopDriveScheduler(
    private val scope: CoroutineScope,
    private val report: (String) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
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
        periodic = (schedule as? DriveAppDataSchedule.Periodic)?.let { rule -> scope.launch { runPeriodic(rule.days) } }
    }

    override suspend fun enqueueNow(accountName: String) = lock.withLock {
        if (manual?.isActive == true) return@withLock
        manual = scope.launch { runWithRetries(accountName) }
    }

    override suspend fun cancelAll() = lock.withLock {
        periodic?.cancel()
        manual?.cancel()
        periodic = null
        manual = null
    }

    private suspend fun runPeriodic(days: Int) {
        val interval = days.coerceAtLeast(1) * DAY_MS
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
            runWithRetries(account)
            // A run that did not produce a newer backup waits for the next check instead of retrying at once.
            if ((bound.state.value.settings.backup?.modifiedAtMillis ?: 0L) <= last) delay(CHECK_INTERVAL_MS)
        }
    }

    private suspend fun runWithRetries(accountName: String) {
        val bound = owner ?: return
        var attempt = 0
        while (true) {
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

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
        const val RETRY_BASE_MS = 15_000L
    }
}

/**
 * The Drive preferences and last backup record, in a private file of the instance directory. A file that cannot be
 * read is logged and reads as the defaults, so one damaged file never locks the user out of Drive backup.
 */
internal class DesktopDriveSettingsFile(
    private val path: Path,
    private val report: (String) -> Unit,
) : DriveAppDataSettingsStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(): DriveAppDataSettings = withContext(Dispatchers.IO) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withContext DriveAppDataSettings()
        try {
            val root = json.parseToJsonElement(Files.readString(path)) as JsonObject
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
                frequency = root.text("frequency")?.let { DriveAppDataFrequency.valueOf(it) } ?: DriveAppDataFrequency.MANUAL,
                wifiOnly = (root["wifiOnly"] as? JsonPrimitive)?.booleanOrNull ?: true,
                backup = backup,
                lastFailure = root.text("lastFailure")?.let { DriveAppDataBackupFailure.valueOf(it) },
                lastFailureAtMillis = root.number("lastFailureAtMillis") ?: 0L,
            )
        } catch (failure: Exception) {
            report("drive backup settings unreadable: ${failure.javaClass.simpleName}")
            DriveAppDataSettings()
        }
    }

    override suspend fun write(settings: DriveAppDataSettings) = withContext(Dispatchers.IO + NonCancellable) {
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
        val parent = requireNotNull(path.parent)
        Files.createDirectories(parent)
        val attributes: Array<FileAttribute<*>> = if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            emptyArray()
        }
        val temporary = Files.createTempFile(parent, ".drive-backup-", ".tmp", *attributes)
        try {
            Files.writeString(temporary, body)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
}

/** Dates and sizes in the default locale, as the Android host formats the newest Drive backup. */
internal object DesktopDriveFormatter : DriveAppDataBackupFormatter {
    override fun date(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

    override fun size(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble().coerceAtLeast(0.0)
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit += 1
        }
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = if (unit == 0) 0 else 1 }
        return "${number.format(value)} ${units[unit]}"
    }
}
