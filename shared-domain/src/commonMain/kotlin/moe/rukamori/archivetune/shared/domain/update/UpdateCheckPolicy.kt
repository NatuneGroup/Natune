package moe.rukamori.archivetune.shared.domain.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.UpdateOffer

/** Release-list cache window per channel; the manifest refresh shares it. */
const val RELEASE_CACHE_CHECK_INTERVAL_MS: Long = 6L * 60 * 60 * 1000 // 6h (Stable cadence)
const val NIGHTLY_CACHE_CHECK_INTERVAL_MS: Long = 15L * 60 * 1000 // 15min
const val COMMIT_HISTORY_CACHE_INTERVAL_MS: Long = 15L * 60 * 1000

/** Notification check cadence: Stable builds check hourly-daily, Nightly builds often. */
const val STABLE_UPDATE_CHECK_INTERVAL_MS: Long = 6L * 60 * 60 * 1000
const val NIGHTLY_UPDATE_CHECK_INTERVAL_MS: Long = 15L * 60 * 1000

fun updateCheckIntervalMs(channel: UpdateChannel): Long = when (channel) {
    UpdateChannel.STABLE -> STABLE_UPDATE_CHECK_INTERVAL_MS
    UpdateChannel.NIGHTLY -> NIGHTLY_UPDATE_CHECK_INTERVAL_MS
}

fun releaseCacheIntervalMs(channel: UpdateChannel): Long = when (channel) {
    UpdateChannel.STABLE -> RELEASE_CACHE_CHECK_INTERVAL_MS
    UpdateChannel.NIGHTLY -> NIGHTLY_CACHE_CHECK_INTERVAL_MS
}

/**
 * GitHub rate-limit backoff: only 403/429 with `x-ratelimit-remaining: 0` blocks; the resume time is
 * `x-ratelimit-reset` (epoch seconds) or 60s when the header is missing. Anything else never blocks.
 */
object GitHubRateLimit {
    const val DEFAULT_BACKOFF_MS: Long = 60_000L

    fun backoffUntil(status: Int, remaining: String?, resetEpochSec: String?, nowMs: Long): Long {
        if (status != 403 && status != 429) return 0L
        if (remaining?.trim()?.toIntOrNull() != 0) return 0L
        return (resetEpochSec?.trim()?.toLongOrNull()?.times(1000L)) ?: (nowMs + DEFAULT_BACKOFF_MS)
    }

    fun isBlocked(untilMs: Long, nowMs: Long): Boolean = nowMs < untilMs
}

/** Keys retired with the Canary channel; a stored CANARY selection becomes NIGHTLY once. */
val canaryLegacyKeyNames: Set<String> = setOf(
    "canaryChannelUnlocked",
    "canary_releases_etag",
    "canary_releases_json",
    "canary_releases_last_checked_at",
    "canary_releases_fingerprint",
)

data class CanaryChannelMigration(val channelToNightly: Boolean, val dropKeyNames: Set<String>) {
    val needed: Boolean get() = channelToNightly || dropKeyNames.isNotEmpty()
}

/**
 * Decide the one-time Canary removal: rewrite a stored CANARY channel to NIGHTLY and drop the retired
 * canary cache keys that are actually present. Already-migrated stores decide "not needed" and stay put.
 */
fun canaryChannelMigration(storedChannel: String?, presentLegacyKeyNames: Set<String>): CanaryChannelMigration =
    CanaryChannelMigration(
        channelToNightly = storedChannel == "CANARY",
        dropKeyNames = canaryLegacyKeyNames intersect presentLegacyKeyNames,
    )

/**
 * Periodic notification gate, ported from UpdateCheckWorker + UpdateNotificationManager:
 * - a check is due only when the last check for THIS channel is older than its interval (checking the
 *   other channel never throttles this one);
 * - an offer is surfaced once per offer identity (channel|tag|code) — [shouldNotifyUpdate] dedups.
 */
fun updateCheckDue(lastCheckedAtMs: Long, lastCheckedChannel: String?, channel: UpdateChannel, nowMs: Long): Boolean =
    lastCheckedChannel != channel.name || (nowMs - lastCheckedAtMs) >= updateCheckIntervalMs(channel)

fun shouldNotifyUpdate(lastNotifiedIdentity: String?, offerIdentity: String): Boolean =
    lastNotifiedIdentity != offerIdentity

/**
 * Launch-popup gate for the persistent "update available" sheet: show when a real offer exists and
 * (a) keep-reminding is on, or (b) this identity was never shown or dismissed. `neverShow` wins over
 * both — a "don't show again for this release" is never undone by the persistent toggle.
 */
fun updatePopupShouldShow(
    offerIdentity: String?,
    persistentRemind: Boolean,
    lastShownIdentity: String?,
    neverShowIdentity: String?,
): Boolean {
    val offer = offerIdentity ?: return false
    if (offer == neverShowIdentity) return false
    return persistentRemind || offer != lastShownIdentity
}

/** Download lifecycle shared by the UI progress dialog and the platform install ports. */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val fraction: Float?, val offer: UpdateOffer) : UpdateDownloadState
    data class ReadyToInstall(val reused: Boolean, val offer: UpdateOffer) : UpdateDownloadState
    data class Failed(val message: String?, val offer: UpdateOffer) : UpdateDownloadState
}

/**
 * How this platform installs an offer — the single decision every host and the UI agree on:
 * - [DIRECT_INSTALL]: the app downloads the artifact and launches the platform installer itself
 *   (Android in-app APK install; desktop opens the downloaded installer package natively).
 * - [OPEN_URL]: the user lands on a URL (release page / artifact link) and installs manually (foss).
 * - [UNAVAILABLE]: honest "updates are not available in this build" (iOS, non-updater builds).
 */
enum class UpdateInstallMode { DIRECT_INSTALL, OPEN_URL, UNAVAILABLE }

/**
 * The host build's update identity: which channel it defaults to, whether the updater runs at all,
 * how artifacts install, and which manifest asset it may take. [artifactNameFor] is the exact release
 * asset name for fixed-name platforms (Android APKs); [artifactMatcher] covers version-stamped names
 * (desktop `natune-<platform>-<arch>-<version>.<ext>`). An exact-name match wins over a predicate hit.
 */
data class UpdateInstallProfile(
    val updaterAvailable: Boolean,
    val installMode: UpdateInstallMode,
    val isNightlyBuild: Boolean,
    val installedCode: Int,
    val versionName: String,
    /** Exact release asset name per channel (`app-…-release.apk` vs `app-…-nightly.apk`); null for predicate matching. */
    val artifactNameFor: (UpdateChannel) -> String? = { null },
    /** Version-stamped names (desktop `natune-<platform>-<arch>-<version>.<ext>`); used when the exact name misses. */
    val artifactMatcher: ((String) -> Boolean)? = null,
)

/**
 * Single-flight update job owner: one download/install at a time, late results are dropped.
 * [start] rejects while a job is active; terminal writes apply only while the job is still active,
 * so a cancelled download can never leave the state machine mid-flight. Job and state move together
 * in one MutableStateFlow cell, so every transition is a single atomic compare-and-set on any platform.
 */
class UpdateAttemptOwner<S>(private val scope: CoroutineScope, private val idle: S) {
    private data class Attempt<S>(val job: Job?, val state: S)

    private val attempt = MutableStateFlow(Attempt<S>(null, idle))
    val stateFlow: StateFlow<S> = attempt.map { it.state }
        .stateIn(scope, SharingStarted.Eagerly, idle)

    fun isActive(): Boolean = attempt.value.job?.isActive == true

    fun start(downloading: S, work: suspend () -> Unit): Boolean {
        while (true) {
            val current = attempt.value
            if (current.job?.isActive == true) return false
            val job = scope.launch(start = CoroutineStart.LAZY) { work() }
            if (attempt.compareAndSet(current, Attempt(job, downloading))) {
                job.start()
                return true
            }
            job.cancel()
        }
    }

    /** Terminal write from inside the running job; no-ops once the job was cancelled. */
    fun update(value: S): Boolean =
        attempt.getAndUpdate { current ->
            if (current.job?.isActive == true) current.copy(state = value) else current
        }.job?.isActive == true

    fun cancel(value: S) {
        attempt.getAndUpdate { Attempt(null, value) }.job?.cancel()
    }

    /** Drop a terminal state back to idle (the "offer" it carried was consumed or dismissed). */
    fun acknowledge(canClear: (S) -> Boolean) {
        attempt.getAndUpdate { current ->
            if (canClear(current.state)) Attempt(null, idle) else current
        }
    }
}
