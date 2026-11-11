package moe.rukamori.archivetune.discord

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Native API is compile-gated. All calls and callback pumping are serialized on Android's main thread. */
internal object DiscordNativeSdkBridge {
    val available: Boolean by lazy {
        BuildConfig.DISCORD_SOCIAL_SDK_AVAILABLE && runCatching { System.loadLibrary("natune_discord_social") }.isSuccess
    }
    val ready = MutableStateFlow(false)
    // Lazy: touching Dispatchers.Main.immediate in the initializer breaks SDK-free JVM unit tests.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var pump: Job? = null
    private var initialized = false
    var authorizationInProgress = false
        private set
    private val sequence = AtomicLong()
    private val operations = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    internal val grants = ConcurrentHashMap<Long, CompletableDeferred<DiscordSdkTokenGrant>>()
    var onDisconnected: (() -> Unit)? = null

    private fun initialize() {
        check(available) { "Discord Social SDK is unavailable" }
        if (!initialized) {
            check(nativeInit(BuildConfig.DISCORD_APPLICATION_ID_LONG)) { "Discord Social SDK initialization failed" }
            initialized = true
        }
        if (pump?.isActive != true) pump = scope.launch {
            while (isActive) { nativeTick(); delay(200L) }
        }
    }

    suspend fun authorize(): DiscordSdkTokenGrant = withContext(Dispatchers.Main.immediate) {
        authorizationInProgress = true
        try { tokenOperation(5 * 60_000L) { nativeAuthorize(it) } } finally { authorizationInProgress = false }
    }
    suspend fun refresh(refreshToken: String): DiscordSdkTokenGrant = tokenOperation { nativeRefresh(refreshToken, it) }
    suspend fun updateToken(accessToken: String) = operation { nativeSetToken(accessToken, it) }
    suspend fun awaitReady() { withTimeout(20_000L) { ready.filter { it }.first() } }

    suspend fun updatePresence(activity: DiscordPresenceActivity) = operation { id ->
        val buttons = activity.buttons.take(2)
        nativeUpdatePresence(activity.type.nativeValue, activity.details.orEmpty(), activity.state.orEmpty(),
            activity.assets.largeImage.orEmpty(), activity.assets.largeText.orEmpty(),
            activity.assets.smallImage.orEmpty(), activity.assets.smallText.orEmpty(),
            activity.timestamps.startEpochSeconds?.times(1000L) ?: 0L,
            activity.timestamps.endEpochSeconds?.times(1000L) ?: 0L,
            buttons.map { it.label }.toTypedArray(), buttons.map { it.url }.toTypedArray(), id)
    }
    suspend fun clearPresence() = operation { nativeClear(it) }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        pump?.cancel(); pump = null
        if (initialized) nativeClose()
        initialized = false
        ready.value = false
        operations.values.forEach { it.cancel() }; operations.clear()
        // Fail, don't cancel: refreshCatching rethrows a cancellation, which would skip the HTTP fallback.
        grants.values.forEach { it.completeExceptionally(IllegalStateException("SDK_CLOSED")) }; grants.clear()
    }

    private suspend fun operation(start: (Long) -> Unit) = withContext(Dispatchers.Main.immediate) {
        initialize()
        val id = sequence.incrementAndGet()
        val result = CompletableDeferred<Unit>()
        operations[id] = result
        try { start(id); withTimeout(20_000L) { result.await() } } finally { operations.remove(id) }
    }

    private suspend fun tokenOperation(timeoutMs: Long = 20_000L, start: (Long) -> Unit): DiscordSdkTokenGrant = withContext(Dispatchers.Main.immediate) {
        initialize()
        val id = sequence.incrementAndGet()
        val result = CompletableDeferred<DiscordSdkTokenGrant>()
        grants[id] = result
        // Mobile authorization waits for the user's browser/SDK activity, unlike a transport operation.
        try { start(id); withTimeout(timeoutMs) { result.await() } } finally { grants.remove(id) }
    }

    @JvmStatic private fun onOperation(id: Long, success: Boolean, error: String) {
        val result = operations.remove(id) ?: return
        if (success) result.complete(Unit) else result.completeExceptionally(IllegalStateException(error))
    }
    @JvmStatic private fun onToken(id: Long, success: Boolean, access: String, refresh: String, expiresIn: Long, error: String) {
        val result = grants.remove(id) ?: return
        if (success && access.isNotBlank()) result.complete(DiscordSdkTokenGrant(access, refresh.takeIf(String::isNotBlank), expiresIn))
        else result.completeExceptionally(IllegalStateException(error))
    }
    @JvmStatic private fun onStatus(isReady: Boolean) {
        val wasReady = ready.value
        ready.value = isReady
        if (wasReady && !isReady) onDisconnected?.invoke()
    }

    @JvmStatic private external fun nativeInit(applicationId: Long): Boolean
    @JvmStatic private external fun nativeAuthorize(id: Long)
    @JvmStatic private external fun nativeRefresh(refreshToken: String, id: Long)
    @JvmStatic private external fun nativeSetToken(accessToken: String, id: Long)
    @JvmStatic private external fun nativeUpdatePresence(type: Int, details: String, state: String,
        largeImage: String, largeText: String, smallImage: String, smallText: String, start: Long, end: Long,
        labels: Array<String>, urls: Array<String>, id: Long)
    @JvmStatic private external fun nativeClear(id: Long)
    @JvmStatic private external fun nativeTick()
    @JvmStatic private external fun nativeClose()
}

internal class DiscordSdkTokenGrant(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long) {
    override fun toString(): String = "DiscordSdkTokenGrant(redacted)"
}
