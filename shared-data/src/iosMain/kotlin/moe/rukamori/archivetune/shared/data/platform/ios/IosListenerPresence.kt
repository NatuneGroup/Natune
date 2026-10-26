@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.coroutines.resume
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceKeys
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresencePolicy
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceState
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceStore
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceTransport
import platform.Foundation.*
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * The heartbeat origin this build was given, or an empty string. Xcode writes it into the Info.plist from the
 * ARCHIVETUNE_LISTENER_PRESENCE_URL build setting. There is no built-in endpoint, so a build without it sends nothing.
 */
fun iosListenerPresenceEndpoint(): String {
    val value = (NSBundle.mainBundle.objectForInfoDictionaryKey("ArchiveTuneListenerPresenceUrl") as? String)?.trim().orEmpty()
    // An unset build setting can reach the plist as its literal reference, which is not an endpoint.
    return if (value.startsWith("$(")) "" else value
}

/**
 * The directory of the opt-in document, under Application Support and excluded from device backup, because the
 * random identifier must stay out of backed-up storage.
 */
fun iosListenerPresenceDirectory(): String {
    val manager = NSFileManager.defaultManager
    val support = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path
        ?: error("Application Support is unavailable")
    val root = "$support/listener-presence"
    manager.createDirectoryAtPath(root, true, null, null)
    NSURL.fileURLWithPath(root, isDirectory = true)
        .setResourceValue(NSNumber.numberWithBool(true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    return root
}

/** Bytes from the system's secure generator, so the identifier is never drawn from a predictable source. */
internal fun iosSecureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    if (count == 0) return bytes
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.toULong(), it.addressOf(0)) }
    check(status == errSecSuccess) { "Secure random bytes are unavailable" }
    return bytes
}

/**
 * The iOS opt-in document. It is a file of its own, not part of the Keychain-backed settings, so the identifier never
 * travels with a settings export. A write replaces the whole document atomically, and the new state is published only
 * after the write succeeds.
 */
class IosListenerPresenceStore(
    directory: String,
    private val newListenerId: () -> String = { ListenerPresencePolicy.newListenerId(::iosSecureRandomBytes) },
) : ListenerPresenceStore {
    private val path = "$directory/$STORE_FILE"
    private val lock = Mutex()
    private val current = MutableStateFlow<ListenerPresenceState?>(null)

    override val state: Flow<ListenerPresenceState> =
        current.onSubscription { loaded() }.filterNotNull().distinctUntilChanged()

    override suspend fun enable() = transition { ListenerPresencePolicy.enable(it, newListenerId) }

    override suspend fun disable() = transition { ListenerPresencePolicy.disable() }

    override suspend fun resetId() = transition { ListenerPresencePolicy.resetId(it, newListenerId) }

    private suspend fun loaded(): ListenerPresenceState = lock.withLock { current.value ?: read().also { current.value = it } }

    private suspend fun transition(next: (ListenerPresenceState) -> ListenerPresenceState) {
        lock.withLock {
            val before = current.value ?: read()
            val after = next(before)
            if (after != before) write(after)
            current.value = after
        }
    }

    private suspend fun read(): ListenerPresenceState = withContext(Dispatchers.Default) {
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext ListenerPresenceState.Off
        val bytes = if (data.length == 0uL) byteArrayOf() else data.bytes?.readBytes(data.length.toInt()) ?: byteArrayOf()
        val values = bytes.decodeToString().lineSequence()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
            .toMap()
        // An unreadable or partial document counts as no consent. The next switch-on writes a fresh one.
        ListenerPresenceState(
            enabled = values[ListenerPresenceKeys.ENABLED] == "true",
            listenerId = values[ListenerPresenceKeys.LISTENER_ID]?.takeIf { it.isNotEmpty() },
            consentVersion = values[ListenerPresenceKeys.CONSENT_VERSION]?.toIntOrNull() ?: 0,
        )
    }

    private suspend fun write(state: ListenerPresenceState) = withContext(Dispatchers.Default) {
        val text = buildString {
            append(ListenerPresenceKeys.ENABLED).append('=').append(state.enabled).append('\n')
            append(ListenerPresenceKeys.CONSENT_VERSION).append('=').append(state.consentVersion).append('\n')
            state.listenerId?.let { append(ListenerPresenceKeys.LISTENER_ID).append('=').append(it).append('\n') }
        }
        val bytes = text.encodeToByteArray()
        bytes.usePinned { pinned ->
            val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                check(
                    data.writeToFile(
                        path,
                        options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication,
                        error = error.ptr,
                    ),
                ) { "The listener presence opt-in was not saved" }
            }
        }
    }

    private companion object {
        const val STORE_FILE = "listener-presence.properties"
    }
}

/**
 * Posts listener-presence payloads over an ephemeral NSURLSession that keeps no cookie, cache or credential, as the
 * transport contract requires. A blank endpoint or one that is not HTTPS leaves [available] false, and nothing is
 * sent. Failures are returned and never retried: the next playback tick sends a fresh payload.
 */
class IosListenerPresenceTransport(apiUrl: String) : ListenerPresenceTransport {
    private val origin = apiUrl.trim().trimEnd('/')

    override val available: Boolean = origin.startsWith(HTTPS_PREFIX) && origin.length > HTTPS_PREFIX.length

    private val session: NSURLSession by lazy {
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration
        configuration.URLCache = null
        configuration.HTTPCookieStorage = null
        configuration.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
        configuration.HTTPShouldSetCookies = false
        configuration.URLCredentialStorage = null
        configuration.timeoutIntervalForRequest = REQUEST_TIMEOUT_MS / 1000.0
        NSURLSession.sessionWithConfiguration(configuration)
    }

    override suspend fun send(payloadJson: String): Result<Unit> {
        if (!available) return Result.failure(IllegalStateException("Listener presence is not configured"))
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { post(payloadJson) }
                ?: Result.failure(IllegalStateException("Listener presence timed out"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private suspend fun post(payloadJson: String): Result<Unit> {
        val url = NSURL.URLWithString(origin + HEARTBEAT_PATH)
            ?: return Result.failure(IllegalStateException("Listener presence endpoint is not a URL"))
        val bytes = payloadJson.encodeToByteArray()
        val body = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        val request = NSMutableURLRequest(
            uRL = url,
            cachePolicy = platform.Foundation.NSURLRequestUseProtocolCachePolicy,
            timeoutInterval = REQUEST_TIMEOUT_MS / 1000.0,
        )
        request.HTTPMethod = "POST"
        request.HTTPShouldHandleCookies = false
        request.setValue("application/json", forHTTPHeaderField = "Content-Type")
        request.HTTPBody = body
        return suspendCancellableCoroutine<Result<Unit>> { continuation ->
            val task = session.dataTaskWithRequest(request) { _, response, error ->
                val code = (response as? NSHTTPURLResponse)?.statusCode ?: 0L
                val result = when {
                    error != null -> Result.failure(IllegalStateException("Listener presence request failed"))
                    code in 200L..299L -> Result.success(Unit)
                    code == 429L -> Result.failure(IllegalStateException("Listener presence rate limited"))
                    else -> Result.failure(IllegalStateException("Listener presence rejected ($code)"))
                }
                if (continuation.isActive) continuation.resume(result)
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    /** Releases the session once its last request ends. Call it after the controller's loop has finished. */
    fun close() {
        if (available) session.finishTasksAndInvalidate()
    }

    private companion object {
        const val HTTPS_PREFIX = "https://"
        const val HEARTBEAT_PATH = "/api/heartbeat"
        const val REQUEST_TIMEOUT_MS = 10_000L
    }
}
