package moe.rukamori.archivetune.shared.data.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import moe.rukamori.archivetune.shared.domain.settings.GitHubCommitsBranchKey
import moe.rukamori.archivetune.shared.domain.settings.GitHubCommitsEtagKey
import moe.rukamori.archivetune.shared.domain.settings.GitHubCommitsJsonKey
import moe.rukamori.archivetune.shared.domain.settings.GitHubCommitsLastCheckedAtKey
import moe.rukamori.archivetune.shared.domain.settings.GitHubRateLimitedUntilKey
import moe.rukamori.archivetune.shared.domain.settings.GitHubReleasesEtagKey
import moe.rukamori.archivetune.shared.domain.settings.GitHubReleasesFingerprintKey
import moe.rukamori.archivetune.shared.domain.settings.GitHubReleasesJsonKey
import moe.rukamori.archivetune.shared.domain.settings.GitHubReleasesLastCheckedAtKey
import moe.rukamori.archivetune.shared.domain.settings.LastNotifiedVersionKey
import moe.rukamori.archivetune.shared.domain.settings.LastUpdateCheckChannelKey
import moe.rukamori.archivetune.shared.domain.settings.LastUpdateCheckKey
import moe.rukamori.archivetune.shared.domain.settings.NightlyReleasesEtagKey
import moe.rukamori.archivetune.shared.domain.settings.NightlyReleasesFingerprintKey
import moe.rukamori.archivetune.shared.domain.settings.NightlyReleasesJsonKey
import moe.rukamori.archivetune.shared.domain.settings.NightlyReleasesLastCheckedAtKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.UpdateFeedManifestNightlyJsonKey
import moe.rukamori.archivetune.shared.domain.settings.UpdateFeedManifestNightlyLastCheckedAtKey
import moe.rukamori.archivetune.shared.domain.settings.UpdateFeedManifestStableJsonKey
import moe.rukamori.archivetune.shared.domain.settings.UpdateFeedManifestStableLastCheckedAtKey
import moe.rukamori.archivetune.shared.domain.settings.UpdateOffer
import moe.rukamori.archivetune.shared.domain.settings.eligibleUpdateOffer
import moe.rukamori.archivetune.shared.domain.settings.selectedUpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.setUpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.updateRequestIsCurrent
import moe.rukamori.archivetune.shared.domain.update.COMMIT_HISTORY_CACHE_INTERVAL_MS
import moe.rukamori.archivetune.shared.domain.update.GitCommit
import moe.rukamori.archivetune.shared.domain.update.GitHubRateLimit
import moe.rukamori.archivetune.shared.domain.update.PreChannel
import moe.rukamori.archivetune.shared.domain.update.ReleaseInfo
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallMode
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallProfile
import moe.rukamori.archivetune.shared.domain.update.UpdateRepository
import moe.rukamori.archivetune.shared.domain.update.branchForChannel
import moe.rukamori.archivetune.shared.domain.update.encodeCommitHistory
import moe.rukamori.archivetune.shared.domain.update.encodeReleasesJson
import moe.rukamori.archivetune.shared.domain.update.findLatestPreRelease
import moe.rukamori.archivetune.shared.domain.update.findLatestRelease
import moe.rukamori.archivetune.shared.domain.update.getPreReleaseVersionName
import moe.rukamori.archivetune.shared.domain.update.getReleaseVersionName
import moe.rukamori.archivetune.shared.domain.update.mergeManifestRelease
import moe.rukamori.archivetune.shared.domain.update.parseCommitsApiJson
import moe.rukamori.archivetune.shared.domain.update.parseCommitHistoryJson
import moe.rukamori.archivetune.shared.domain.update.parseReleasesJson
import moe.rukamori.archivetune.shared.domain.update.parseUpdateFeedManifest
import moe.rukamori.archivetune.shared.domain.update.preReleaseTopFingerprint
import moe.rukamori.archivetune.shared.domain.update.releaseBuildCode
import moe.rukamori.archivetune.shared.domain.update.releaseCacheIntervalMs
import moe.rukamori.archivetune.shared.domain.update.selectUpdateFeedAsset
import moe.rukamori.archivetune.shared.domain.update.shouldNotifyUpdate
import moe.rukamori.archivetune.shared.domain.update.toReleaseInfo
import moe.rukamori.archivetune.shared.domain.update.topReleaseFingerprint
import moe.rukamori.archivetune.shared.domain.update.updateCheckDue
import moe.rukamori.archivetune.shared.domain.update.updateFeedManifestUrl

private data class ReleasesNetworkResult(
    val status: HttpStatusCode,
    val body: String?,
    val etag: String?,
    val rateLimitRemaining: String?,
    val rateLimitReset: String?,
)

private data class UpdateFeedFetch(val rawJson: String, val release: ReleaseInfo)

private fun PreferenceSnapshot?.orEmpty(): PreferenceSnapshot = this ?: PreferenceSnapshot.EMPTY

/**
 * The updater engine every non-Android host shares, ported from `utils/Updater.kt` with the same
 * fetch order and the same cache semantics: TTL → channel manifest (`nightly.json`/`stable.json` on
 * the fixed `update-feed` release) → rate-limit backoff → ETag'd api.github.com page → fingerprint-
 * gated cache writes. Failure paths serve the merged cache before erroring, exactly like the app.
 *
 * The host supplies [profile] (build identity, install mode, artifact selection) and an injected
 * [client]; channel selection lives in the shared [settings] store under `updateChannel`.
 */
class SharedUpdateChecker(
    private val client: HttpClient,
    private val settings: SettingsStore,
    val profile: UpdateInstallProfile,
    private val nowMs: () -> Long,
) {
    private val channelCheckTimes = mutableMapOf<UpdateChannel, Long>()
    fun lastCheckTime(channel: UpdateChannel): Long = channelCheckTimes[channel] ?: -1L

    private val latestOffers = mutableMapOf<UpdateChannel, UpdateOffer>()
    fun cachedOffer(channel: UpdateChannel): UpdateOffer? = latestOffers[channel]

    // A revision rejects delayed results even after an A → B → A switch. The increment is atomic,
    // so an A → B → A flip can never produce a revision a stale request would accept.
    private val selectionEpoch = MutableStateFlow(0L)
    val selectionChanges: StateFlow<Long> = selectionEpoch.asStateFlow()
    val selectionRevision: Long get() = selectionEpoch.value

    private fun snapshot(): PreferenceSnapshot? = settings.snapshots.value

    fun selectedChannel(): UpdateChannel =
        snapshot().orEmpty().selectedUpdateChannel(profile.isNightlyBuild)

    /**
     * The page writes the channel itself through the settings controller; it calls this so a late
     * answer for the previous channel is still rejected — the same kill the app's epoch bump performs.
     */
    fun markSelectionChanged() {
        selectionEpoch.update { it + 1L }
    }

    /** Channel writes bump the selection revision first, exactly like the app — late feed results die. */
    suspend fun selectChannel(channel: UpdateChannel) {
        val differs = snapshot().orEmpty().selectedUpdateChannel(profile.isNightlyBuild) != channel
        if (!differs) return
        selectionEpoch.update { it + 1L }
        settings.edit { setUpdateChannel(channel) }
    }

    suspend fun isCurrentSelection(channel: UpdateChannel, revision: Long): Boolean =
        runCatchingCancellable {
            val current = snapshot().orEmpty().selectedUpdateChannel(profile.isNightlyBuild)
            updateRequestIsCurrent(channel, revision, current, selectionRevision)
        }.getOrDefault(false)

    /**
     * Newest release for [channel]; when the request's selection is still current the answer is also
     * recorded as [cachedOffer]. Returns null when the selection moved on mid-flight or no release
     * matched — callers re-check on the new channel anyway.
     */
    suspend fun latestRelease(channel: UpdateChannel, forceRefresh: Boolean = false): ReleaseInfo? {
        val revision = selectionRevision
        val release = when (channel) {
            UpdateChannel.STABLE -> allReleases(forceRefresh = forceRefresh).getOrThrow().let(::findLatestRelease)
            UpdateChannel.NIGHTLY -> allPreReleases(forceRefresh = forceRefresh).getOrThrow()
                .let { findLatestPreRelease(it, PreChannel.NIGHTLY) }
        }
        if (release != null && isCurrentSelection(channel, revision)) {
            rememberOffer(release, channel)
            return release
        }
        return null
    }

    fun releaseVersionName(release: ReleaseInfo, channel: UpdateChannel): String = when (channel) {
        UpdateChannel.STABLE -> getReleaseVersionName(release)
        UpdateChannel.NIGHTLY -> getPreReleaseVersionName(release)
    }

    /**
     * Offer semantics, ported exactly: only a verified build code strictly above the installed one is
     * offered (never a downgrade, never a same-code re-prompt), and the install URL is the direct
     * artifact link only when this build installs artifacts itself — [UpdateInstallMode.OPEN_URL]
     * hosts land on the release page.
     */
    fun eligibleOffer(release: ReleaseInfo, channel: UpdateChannel): UpdateOffer? {
        if (!profile.updaterAvailable) return null
        val code = releaseBuildCode(release, channel)
        val url = when (profile.installMode) {
            UpdateInstallMode.DIRECT_INSTALL -> release.downloadUrl
            UpdateInstallMode.OPEN_URL -> release.htmlUrl
            UpdateInstallMode.UNAVAILABLE -> null
        }
        // The digest describes the feed's artifact, so it only travels with a direct artifact link; an
        // OPEN_URL host sends the user to a page and would have nothing to hash.
        val sha256 = if (profile.installMode == UpdateInstallMode.DIRECT_INSTALL) release.sha256 else null
        return eligibleUpdateOffer(
            channel, release.tagName, code, releaseVersionName(release, channel), release.body, url,
            profile.installedCode, sha256,
        )
    }

    private fun rememberOffer(release: ReleaseInfo, channel: UpdateChannel) {
        val offer = eligibleOffer(release, channel)
        if (offer == null) latestOffers.remove(channel) else latestOffers[channel] = offer
    }

    suspend fun cachedReleases(): List<ReleaseInfo> =
        snapshot()?.get(GitHubReleasesJsonKey)
            ?.takeIf { it.isNotBlank() }
            ?.let { parseReleasesJson(it, profile.artifactNameFor(UpdateChannel.STABLE), profile.artifactMatcher) }
            ?: emptyList()

    suspend fun cachedPreReleases(): List<ReleaseInfo> =
        snapshot()?.get(NightlyReleasesJsonKey)
            ?.takeIf { it.isNotBlank() }
            ?.let { parseReleasesJson(it, profile.artifactNameFor(UpdateChannel.NIGHTLY), profile.artifactMatcher) }
            ?: emptyList()

    // ── Channel-scoped manifest keys ──────────────────────────────────────────────────────

    private fun manifestJsonKey(channel: UpdateChannel) = when (channel) {
        UpdateChannel.NIGHTLY -> UpdateFeedManifestNightlyJsonKey
        UpdateChannel.STABLE -> UpdateFeedManifestStableJsonKey
    }

    private fun manifestCheckedAtKey(channel: UpdateChannel) = when (channel) {
        UpdateChannel.NIGHTLY -> UpdateFeedManifestNightlyLastCheckedAtKey
        UpdateChannel.STABLE -> UpdateFeedManifestStableLastCheckedAtKey
    }

    /** Map raw manifest JSON into this build's release model; a missing artifact for THIS build is a miss. */
    private fun mapUpdateFeedBody(body: String, channel: UpdateChannel): ReleaseInfo? {
        val manifest = parseUpdateFeedManifest(body, channel) ?: return null
        val asset = selectUpdateFeedAsset(
            manifest.assets, profile.artifactNameFor(channel), profile.artifactMatcher,
        ) ?: return null
        return manifest.toReleaseInfo(asset.url, UpdateRepository.RELEASES_BASE_URL, asset.sha256)
    }

    private fun readCachedUpdateFeed(channel: UpdateChannel): ReleaseInfo? =
        snapshot()?.get(manifestJsonKey(channel))
            ?.takeIf { it.isNotBlank() }
            ?.let { mapUpdateFeedBody(it, channel) }

    private fun isUpdateFeedCacheFresh(channel: UpdateChannel, now: Long): Boolean {
        val lastCheckedAt = snapshot()?.get(manifestCheckedAtKey(channel), 0L) ?: 0L
        return lastCheckedAt > 0 && now - lastCheckedAt < releaseCacheIntervalMs(channel)
    }

    /**
     * Fetch the channel manifest from the fixed `update-feed` release asset — CDN-served,
     * unauthenticated and not API-metered, so it is tried before every api.github.com call.
     */
    private suspend fun fetchUpdateFeed(channel: UpdateChannel): UpdateFeedFetch? =
        try {
            val response = client.get(updateFeedManifestUrl(channel)) {
                headers {
                    append("Accept", UpdateRepository.API_ACCEPT)
                    append("User-Agent", UpdateRepository.API_USER_AGENT)
                }
            }
            if (response.status.value !in 200..299) return null
            val body = response.bodyAsText()
            mapUpdateFeedBody(body, channel)?.let { UpdateFeedFetch(rawJson = body, release = it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    // ── Release list: Stable ─────────────────────────────────────────────────────────────

    suspend fun allReleases(perPage: Int = 30, forceRefresh: Boolean = false): Result<List<ReleaseInfo>> {
        if (!profile.updaterAvailable) return Result.success(emptyList())
        return runCatchingCancellable {
            val now = nowMs()
            val snap = snapshot()
            val cachedJson = snap?.get(GitHubReleasesJsonKey)
            val cachedEtag = snap?.get(GitHubReleasesEtagKey)
            val lastCheckedAt = snap?.get(GitHubReleasesLastCheckedAtKey, 0L) ?: 0L
            val cachedFingerprint = snap?.get(GitHubReleasesFingerprintKey)
            val cachedReleases = cachedJson?.takeIf { it.isNotBlank() }
                ?.let { parseReleasesJson(it, profile.artifactNameFor(UpdateChannel.STABLE), profile.artifactMatcher) }

            val feedChannel = UpdateChannel.STABLE
            val cachedManifestRelease = readCachedUpdateFeed(feedChannel)
            val manifestFresh = isUpdateFeedCacheFresh(feedChannel, now)
            val releasesFresh = !cachedJson.isNullOrBlank() &&
                (now - lastCheckedAt) < releaseCacheIntervalMs(feedChannel)

            if (!forceRefresh && (manifestFresh || releasesFresh)) {
                channelCheckTimes[feedChannel] = now
                return@runCatchingCancellable mergeManifestRelease(cachedReleases ?: emptyList(), cachedManifestRelease)
            }

            // Manifest first: the fixed feed asset avoids the API and its rate limit.
            fetchUpdateFeed(feedChannel)?.let { fetched ->
                val merged = mergeManifestRelease(cachedReleases ?: emptyList(), fetched.release)
                settings.edit {
                    this[manifestJsonKey(feedChannel)] = fetched.rawJson
                    this[manifestCheckedAtKey(feedChannel)] = now
                    this[GitHubReleasesLastCheckedAtKey] = now
                    this[GitHubReleasesJsonKey] = encodeReleasesJson(merged)
                    this[GitHubReleasesFingerprintKey] = topReleaseFingerprint(merged) ?: ""
                }
                channelCheckTimes[feedChannel] = now
                return@runCatchingCancellable merged
            }

            // Manifest miss: while the rate-limit backoff is active the API is off limits entirely.
            if (rateLimitBackoffActive(now)) {
                channelCheckTimes[feedChannel] = now
                val cached = mergeManifestRelease(cachedReleases ?: emptyList(), cachedManifestRelease)
                if (cached.isNotEmpty()) return@runCatchingCancellable cached
                throw IllegalStateException("GitHub API is rate limited and no cached releases exist")
            }

            val networkResult = try {
                fetchReleasesNetwork(perPage, cachedEtag)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            networkResult?.let {
                recordRateLimitBackoff(it.status.value, it.rateLimitRemaining, it.rateLimitReset, now)
            }
                ?: run {
                    val cached = mergeManifestRelease(cachedReleases ?: emptyList(), cachedManifestRelease)
                    if (cached.isNotEmpty()) {
                        channelCheckTimes[feedChannel] = now
                        return@runCatchingCancellable cached
                    }
                    throw IllegalStateException("Failed to fetch releases")
                }

            when {
                networkResult.status == HttpStatusCode.NotModified -> {
                    settings.edit {
                        this[GitHubReleasesLastCheckedAtKey] = now
                        networkResult.etag?.let { e -> this[GitHubReleasesEtagKey] = e }
                    }
                    val cached = mergeManifestRelease(cachedReleases ?: emptyList(), cachedManifestRelease)
                    if (cached.isNotEmpty()) {
                        channelCheckTimes[feedChannel] = now
                        return@runCatchingCancellable cached
                    }
                    throw IllegalStateException("Release cache is empty")
                }

                networkResult.status.value in 200..299 && !networkResult.body.isNullOrBlank() -> {
                    val networkBody = networkResult.body
                    val releases = parseReleasesJson(
                        networkBody, profile.artifactNameFor(UpdateChannel.STABLE), profile.artifactMatcher,
                    )
                    val newFingerprint = topReleaseFingerprint(releases)
                    settings.edit {
                        this[GitHubReleasesLastCheckedAtKey] = now
                        networkResult.etag?.let { e -> this[GitHubReleasesEtagKey] = e }
                        if (cachedJson != networkBody || cachedFingerprint != newFingerprint || cachedJson.isNullOrBlank()) {
                            this[GitHubReleasesJsonKey] = networkBody
                            this[GitHubReleasesFingerprintKey] = newFingerprint ?: ""
                        }
                    }
                    channelCheckTimes[feedChannel] = now
                    releases
                }

                else -> {
                    if (cachedReleases != null) {
                        channelCheckTimes[feedChannel] = now
                        cachedReleases
                    } else {
                        throw IllegalStateException("Failed to fetch releases: HTTP ${networkResult.status.value}")
                    }
                }
            }
        }
    }

    // ── Release list: pre-release (Nightly) ──────────────────────────────────────────────

    suspend fun allPreReleases(perPage: Int = 30, forceRefresh: Boolean = false): Result<List<ReleaseInfo>> {
        if (!profile.updaterAvailable) return Result.success(emptyList())
        return runCatchingCancellable {
            val channel = PreChannel.NIGHTLY
            val now = nowMs()
            val snap = snapshot()
            val cachedJson = snap?.get(NightlyReleasesJsonKey)
            val cachedEtag = snap?.get(NightlyReleasesEtagKey)
            val lastCheckedAt = snap?.get(NightlyReleasesLastCheckedAtKey, 0L) ?: 0L
            val cachedFingerprint = snap?.get(NightlyReleasesFingerprintKey)
            val cachedReleases = cachedJson?.takeIf { it.isNotBlank() }
                ?.let { parseReleasesJson(it, profile.artifactNameFor(UpdateChannel.NIGHTLY), profile.artifactMatcher) }

            val feedChannel = UpdateChannel.NIGHTLY
            val cachedManifestRelease = readCachedUpdateFeed(feedChannel)
            val manifestFresh = isUpdateFeedCacheFresh(feedChannel, now)
            val releasesFresh = !cachedJson.isNullOrBlank() &&
                (now - lastCheckedAt) < releaseCacheIntervalMs(feedChannel)

            if (!forceRefresh && (manifestFresh || releasesFresh)) {
                channelCheckTimes[feedChannel] = now
                return@runCatchingCancellable mergeManifestRelease(cachedReleases ?: emptyList(), cachedManifestRelease)
            }

            fetchUpdateFeed(feedChannel)?.let { fetched ->
                val merged = mergeManifestRelease(cachedReleases ?: emptyList(), fetched.release)
                settings.edit {
                    this[manifestJsonKey(feedChannel)] = fetched.rawJson
                    this[manifestCheckedAtKey(feedChannel)] = now
                    this[NightlyReleasesLastCheckedAtKey] = now
                    this[NightlyReleasesJsonKey] = encodeReleasesJson(merged)
                    this[NightlyReleasesFingerprintKey] = preReleaseTopFingerprint(merged, channel) ?: ""
                }
                channelCheckTimes[feedChannel] = now
                return@runCatchingCancellable merged
            }

            if (rateLimitBackoffActive(now)) {
                channelCheckTimes[feedChannel] = now
                val cached = mergeManifestRelease(cachedReleases ?: emptyList(), cachedManifestRelease)
                if (cached.isNotEmpty()) return@runCatchingCancellable cached
                throw IllegalStateException("GitHub API is rate limited and no cached releases exist")
            }

            val networkResult = try {
                fetchReleasesNetwork(perPage, cachedEtag)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            networkResult?.let {
                recordRateLimitBackoff(it.status.value, it.rateLimitRemaining, it.rateLimitReset, now)
            }

            when {
                networkResult?.status == HttpStatusCode.NotModified && cachedReleases != null -> {
                    settings.edit {
                        this[NightlyReleasesLastCheckedAtKey] = now
                        networkResult.etag?.let { e -> this[NightlyReleasesEtagKey] = e }
                    }
                    channelCheckTimes[feedChannel] = now
                    return@runCatchingCancellable mergeManifestRelease(cachedReleases ?: emptyList(), cachedManifestRelease)
                }

                networkResult != null &&
                    networkResult.status.value in 200..299 &&
                    !networkResult.body.isNullOrBlank() -> {
                    val networkBody = networkResult.body
                    val releases = parseReleasesJson(
                        networkBody, profile.artifactNameFor(UpdateChannel.NIGHTLY), profile.artifactMatcher,
                    ).filter { channel.tagRegex.matches(it.tagName) }
                    if (releases.isNotEmpty()) {
                        val newFingerprint = preReleaseTopFingerprint(releases, channel)
                        settings.edit {
                            this[NightlyReleasesLastCheckedAtKey] = now
                            networkResult.etag?.let { e -> this[NightlyReleasesEtagKey] = e }
                            if (cachedJson != networkBody || cachedFingerprint != newFingerprint || cachedJson.isNullOrBlank()) {
                                this[NightlyReleasesJsonKey] = networkBody
                                this[NightlyReleasesFingerprintKey] = newFingerprint ?: ""
                            }
                        }
                        channelCheckTimes[feedChannel] = now
                        return@runCatchingCancellable releases
                    }
                }
            }

            val cached = mergeManifestRelease(cachedReleases ?: emptyList(), cachedManifestRelease)
            if (cached.isNotEmpty()) {
                channelCheckTimes[feedChannel] = now
                return@runCatchingCancellable cached
            }
            throw IllegalStateException("No ${channel.name.lowercase()} update source is currently available")
        }
    }

    // ── Network + rate limit ─────────────────────────────────────────────────────────────

    private suspend fun fetchReleasesNetwork(perPage: Int, cachedEtag: String?): ReleasesNetworkResult {
        val response: HttpResponse = client.get("${UpdateRepository.API_RELEASES_URL}?per_page=$perPage") {
            headers {
                append("Accept", UpdateRepository.API_ACCEPT)
                append("User-Agent", UpdateRepository.API_USER_AGENT)
                if (!cachedEtag.isNullOrBlank()) append("If-None-Match", cachedEtag)
            }
        }
        val etag = response.headers["ETag"]
        val remaining = response.headers["X-RateLimit-Remaining"]
        val reset = response.headers["X-RateLimit-Reset"]
        return if (response.status == HttpStatusCode.NotModified) {
            ReleasesNetworkResult(response.status, null, cachedEtag ?: etag, remaining, reset)
        } else {
            ReleasesNetworkResult(response.status, response.bodyAsText(), etag, remaining, reset)
        }
    }

    private suspend fun rateLimitBackoffActive(now: Long): Boolean =
        GitHubRateLimit.isBlocked(snapshot()?.get(GitHubRateLimitedUntilKey, 0L) ?: 0L, now)

    private suspend fun recordRateLimitBackoff(
        statusCode: Int, rateLimitRemaining: String?, rateLimitReset: String?, nowMs: Long,
    ) {
        val until = GitHubRateLimit.backoffUntil(statusCode, rateLimitRemaining, rateLimitReset, nowMs)
        if (until > 0L) settings.edit { this[GitHubRateLimitedUntilKey] = until }
    }

    // ── Commit history ───────────────────────────────────────────────────────────────────

    /**
     * Recent commits for a channel's branch (`main`/`dev`): per-branch cache + 15min TTL + ETag on
     * the first page only, `Link: rel="next"` pagination up to [count] (0 = unlimited). A usable
     * cache beats an empty or failed page, and during rate-limit backoff the API is off limits.
     */
    suspend fun commitHistory(count: Int, branch: String = branchForChannel(selectedChannel())): Result<List<GitCommit>> =
        runCatchingCancellable {
            if (!profile.updaterAvailable) return@runCatchingCancellable emptyList()
            val now = nowMs()
            val snap = snapshot()
            val cachedBranch = snap?.get(GitHubCommitsBranchKey)
            val cachedJson = snap?.get(GitHubCommitsJsonKey)
            val cachedEtag = snap?.get(GitHubCommitsEtagKey)
            val lastCheckedAt = snap?.get(GitHubCommitsLastCheckedAtKey, 0L) ?: 0L
            val cachedCommits = cachedJson?.takeIf { it.isNotBlank() }?.let(::parseCommitHistoryJson)
            val cachedCommitsForBranch = cachedCommits?.takeIf { cachedBranch == branch }

            if (cachedCommitsForBranch != null && now - lastCheckedAt < COMMIT_HISTORY_CACHE_INTERVAL_MS) {
                return@runCatchingCancellable cachedCommitsForBranch
            }
            if (rateLimitBackoffActive(now)) {
                if (cachedCommitsForBranch != null) return@runCatchingCancellable cachedCommitsForBranch
                throw IllegalStateException("GitHub API is rate limited and no cached commit history exists")
            }

            val perPage = if (count <= 0) 100 else count.coerceAtMost(100)
            val unlimited = count <= 0
            val commits = mutableListOf<GitCommit>()
            var pageUrl: String? =
                "${UpdateRepository.API_COMMITS_URL}?sha=$branch&per_page=$perPage&page=1"
            var firstPageEtag: String? = null
            var onFirstPage = true

            while (pageUrl != null) {
                val response = client.get(pageUrl) {
                    headers {
                        append("Accept", UpdateRepository.API_ACCEPT)
                        append("User-Agent", UpdateRepository.API_USER_AGENT)
                        if (onFirstPage && !cachedEtag.isNullOrBlank() && cachedCommitsForBranch != null) {
                            append("If-None-Match", cachedEtag)
                        }
                    }
                }
                if (onFirstPage) {
                    onFirstPage = false
                    recordRateLimitBackoff(
                        response.status.value,
                        response.headers["X-RateLimit-Remaining"],
                        response.headers["X-RateLimit-Reset"],
                        now,
                    )
                    if (response.status == HttpStatusCode.NotModified && cachedCommitsForBranch != null) {
                        settings.edit {
                            this[GitHubCommitsLastCheckedAtKey] = now
                            response.headers["ETag"]?.let { e -> this[GitHubCommitsEtagKey] = e }
                        }
                        return@runCatchingCancellable cachedCommitsForBranch
                    }
                    if (response.status.value !in 200..299) {
                        if (cachedCommitsForBranch != null) return@runCatchingCancellable cachedCommitsForBranch
                        throw IllegalStateException("Failed to fetch commit history: HTTP ${response.status.value}")
                    }
                    firstPageEtag = response.headers["ETag"]
                }

                val page = parseCommitsApiJson(response.bodyAsText())
                if (page.isEmpty()) break
                for (commit in page) {
                    if (!unlimited && commits.size >= count) break
                    commits += commit
                }
                if (!unlimited && commits.size >= count) break

                pageUrl = response.headers["Link"]?.let { link ->
                    Regex("""<([^>]+)>;\s*rel="next"""").find(link)?.groupValues?.getOrNull(1)
                }
            }

            if (commits.isEmpty()) {
                if (cachedCommitsForBranch != null) return@runCatchingCancellable cachedCommitsForBranch
                return@runCatchingCancellable emptyList()
            }

            val history = if (unlimited || commits.size <= count) commits else commits.take(count)
            settings.edit {
                this[GitHubCommitsBranchKey] = branch
                this[GitHubCommitsJsonKey] = encodeCommitHistory(history)
                firstPageEtag?.let { e -> this[GitHubCommitsEtagKey] = e }
                this[GitHubCommitsLastCheckedAtKey] = now
            }
            history
        }

    // ── Notification check ───────────────────────────────────────────────────────────────

    /**
     * The periodic-check decision UpdateCheckWorker made on Android: throttle per channel via
     * [updateCheckDue], resolve the newest release, dedup by offer identity. The host surfaces the
     * returned offer itself (OS notification, snackbar); the identity is recorded only while the
     * selection is still current, so a stale check can never mark a version as notified.
     */
    suspend fun checkForNotification(): UpdateOffer? {
        if (!profile.updaterAvailable) return null
        val channel = selectedChannel()
        val revision = selectionRevision
        val now = nowMs()
        val snap = snapshot()
        val lastCheck = snap?.get(LastUpdateCheckKey, 0L) ?: 0L
        val lastChannel = snap?.get(LastUpdateCheckChannelKey)
        if (!updateCheckDue(lastCheck, lastChannel, channel, now)) return null
        val release = runCatching { latestRelease(channel) }.getOrNull() ?: return null
        if (!isCurrentSelection(channel, revision)) return null
        val offer = eligibleOffer(release, channel)
        val alreadyNotified = offer == null ||
            !shouldNotifyUpdate(snap?.get(LastNotifiedVersionKey), offer.identity)
        if (alreadyNotified) {
            settings.edit {
                this[LastUpdateCheckKey] = now
                this[LastUpdateCheckChannelKey] = channel.name
            }
            return null
        }
        settings.edit {
            this[LastUpdateCheckKey] = now
            this[LastUpdateCheckChannelKey] = channel.name
            this[LastNotifiedVersionKey] = offer.identity
        }
        return offer
    }

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
}
