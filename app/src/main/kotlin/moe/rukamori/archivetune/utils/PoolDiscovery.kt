package moe.rukamori.archivetune.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import moe.rukamori.archivetune.shared.data.provider.ArchivePoolEndpoints
import moe.rukamori.archivetune.shared.data.provider.PoolDiagnostics
import moe.rukamori.archivetune.shared.data.provider.PoolDiscoveryPayload
import moe.rukamori.archivetune.shared.data.provider.PoolDiscoveryPolicy
import moe.rukamori.archivetune.shared.data.provider.PoolDiscoveryState
import moe.rukamori.archivetune.shared.data.provider.PoolDiscoveryStateCodec
import moe.rukamori.archivetune.shared.data.provider.PoolPolling
import moe.rukamori.archivetune.shared.data.provider.PoolResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * The Android host's blocking side of the shared pool discovery policy: the six-hour jittered
 * re-ask window, the `If-None-Match`/`ETag` revalidation, the single-flight guard the providers
 * lacked (concurrent startup resolves each asked a discovery route on their own), the refusal
 * backoff (`401`/`403` for hours, `429`/`503` for the server's `Retry-After`) and the rule that a
 * backgrounded app does not poll at all.
 *
 * State is persisted in plain preferences so a relaunch inside the window costs no request; the
 * payload shape and the policy both live in shared-data, where the desktop and iOS pools use the
 * same code.
 */
internal object PoolDiscovery {
    private const val TAG = "PoolDiscovery"
    private const val PREFS = "pool_discovery"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val locks = ConcurrentHashMap<String, Any>()

    /** Binds the persisted state and the one-line diagnostics sink; called once from App. */
    fun install(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        PoolDiagnostics.sink = { message -> Timber.tag(TAG).d("%s", message) }
    }

    /** No discovery request is made while the app is backgrounded; the persisted list keeps serving. */
    fun setForeground(foreground: Boolean) {
        PoolPolling.foreground = foreground
    }

    /** The persisted `ETag` of the last accepted body for [path], so a GET can revalidate it. */
    fun feedEtag(path: String): String? = prefs?.getString("etag:$path", null)

    /** Remembers a new `ETag`; an answer without one keeps the previous value. */
    fun rememberFeedEtag(path: String, etag: String?) {
        if (etag.isNullOrBlank()) return
        runCatching { prefs?.edit()?.putString("etag:$path", etag)?.apply() }
    }

    /**
     * The current instance URLs for [service]. Answers the persisted list while the window is open,
     * the app is backgrounded, or a refusal is still cooling down; otherwise one request is shared by
     * every concurrent caller and a failure keeps the previous list. Never throws.
     */
    fun instances(
        service: String,
        path: String,
        userAgent: String,
        client: OkHttpClient,
        dropSquidWtf: Boolean = false,
    ): List<String> {
        val state = load(service)
        if (!PoolDiscoveryPolicy.shouldFetch(state, System.currentTimeMillis(), PoolPolling.foreground)) return state.instances
        val lock = locks.getOrPut(service) { Any() }
        synchronized(lock) {
            // A caller that waited here sees the state the winner just persisted, so it does not pay
            // for a second request.
            val current = load(service)
            if (!PoolDiscoveryPolicy.shouldFetch(current, System.currentTimeMillis(), PoolPolling.foreground)) {
                return current.instances
            }
            return refresh(service, current, path, userAgent, client, dropSquidWtf)
        }
    }

    /** Drops the discovered list (the Playback Pool switch went off), persisted copy included. */
    fun forget(service: String) {
        save(service, PoolDiscoveryState())
    }

    private fun refresh(
        service: String,
        current: PoolDiscoveryState,
        path: String,
        userAgent: String,
        client: OkHttpClient,
        dropSquidWtf: Boolean,
    ): List<String> {
        var answer: PoolResponse? = null
        for (base in PoolEndpoints.bases()) {
            val url = "$base$path"
            val builder = Request.Builder().url(url).header("User-Agent", userAgent)
            // Present the per-app read key when the pool has gating enabled.
            if (BuildConfig.SOURCE_PROVIDER_KEY.isNotBlank()) {
                builder.header("Authorization", "Bearer ${BuildConfig.SOURCE_PROVIDER_KEY}")
            }
            current.etag?.takeIf { it.isNotBlank() }?.let { builder.header("If-None-Match", it) }
            val reply =
                try {
                    client.newCall(builder.get().build()).withAudioSourceAttemptDeadline().execute().use { response ->
                        PoolResponse(
                            status = response.code,
                            body = if (response.code == 200) response.body?.string().orEmpty() else "",
                            retryAfterMs = PoolResponse.retryAfterMs(response.header("Retry-After")),
                            etag = response.header("ETag"),
                        )
                    }
                } catch (failure: Exception) {
                    failure.rethrowIfAudioSourceCancelled()
                    null
                }
            if (reply == null) continue
            if (!PoolAccountManager.isPoolEnabled()) return current.instances
            if (ArchivePoolEndpoints.isFailoverStatus(reply.status)) {
                answer = reply
                continue
            }
            answer = reply
            if (!reply.refused) ArchivePoolEndpoints.remember(url.substringBefore("/api/"))
            break
        }
        val now = System.currentTimeMillis()
        val next =
            when {
                answer == null -> PoolDiscoveryPolicy.nextAfterTransportFailure(current, now)
                answer.notModified -> PoolDiscoveryPolicy.nextAfterNotModified(current, now)
                answer.status in 200..299 -> {
                    val parsed = PoolDiscoveryPayload.urls(answer.body, dropSquidWtf)
                    PoolDiscoveryPolicy.nextAfterSuccess(
                        current,
                        now,
                        parsed.ifEmpty { current.instances },
                        if (parsed.isEmpty()) current.etag else answer.etag,
                    )
                }
                else -> {
                    if (answer.status == 401 || answer.status == 403) {
                        // The whole difference between "nothing contributed" and "this build's key is
                        // wrong" — keep it visible, as the providers' own logs did.
                        Timber.tag(TAG).w(
                            "Pool discovery %s rejected as unauthorized (HTTP %d); " +
                                "SOURCE_PROVIDER_KEY is missing, revoked, or issued by a different deployment.",
                            service,
                            answer.status,
                        )
                    } else {
                        Timber.tag(TAG).w("Pool discovery %s refused (HTTP %d)", service, answer.status)
                    }
                    PoolDiscoveryPolicy.nextAfterRefusal(current, now, answer.status, answer.retryAfterMs)
                }
            }
        save(service, next)
        return next.instances
    }

    private fun load(service: String): PoolDiscoveryState =
        PoolDiscoveryStateCodec.decode(prefs?.getString(service, null)) ?: PoolDiscoveryState()

    private fun save(service: String, state: PoolDiscoveryState) {
        runCatching { prefs?.edit()?.putString(service, PoolDiscoveryStateCodec.encode(state))?.apply() }
    }
}
