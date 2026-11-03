package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.shared.data.update.JvmUpdateInstaller
import moe.rukamori.archivetune.shared.data.update.SharedUpdateChecker
import moe.rukamori.archivetune.shared.data.update.UpdateInstallOutcome
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.UpdateOffer
import moe.rukamori.archivetune.shared.domain.update.UpdateDownloadState
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallMode
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallProfile
import moe.rukamori.archivetune.shared.ui.settings.UpdateCommit
import moe.rukamori.archivetune.shared.ui.settings.UpdateFeedResult
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.Path

/** Channel the desktop artifact matcher uses per release naming: `natune-desktop-<os>-<arch>-<version>.<ext>`. */
internal class DesktopUpdateOwner(
    private val graph: DesktopSessionGraph,
    appDataDir: Path,
) {
    val isNightlyBuild: Boolean = DesktopBuildIdentity.VERSION_NAME.startsWith("N")
    val installedCode: Int = DesktopBuildIdentity.VERSION_CODE

    // A build without a stamped code cannot prove it is behind the feed, so the page stays honestly
    // "unavailable" — same as the app's noBuildCode path instead of guessing from versionName.
    val updaterAvailable: Boolean = installedCode > 0
    val installMode: UpdateInstallMode =
        if (updaterAvailable) UpdateInstallMode.DIRECT_INSTALL else UpdateInstallMode.UNAVAILABLE

    private val osToken = System.getProperty("os.name", "").lowercase(Locale.US).let {
        when {
            it.contains("win") -> "windows"
            it.contains("mac") -> "macos"
            else -> "linux"
        }
    }
    private val archToken = System.getProperty("os.arch", "").lowercase(Locale.US).let {
        if (it.contains("aarch64") || it.contains("arm")) "arm64" else "x64"
    }

    private val profile = UpdateInstallProfile(
        updaterAvailable = updaterAvailable,
        installMode = installMode,
        isNightlyBuild = isNightlyBuild,
        installedCode = installedCode,
        versionName = DesktopBuildIdentity.VERSION_NAME,
        artifactMatcher = ::matchesDesktopArtifact,
    )

    val checker = SharedUpdateChecker(
        client = graph.http,
        settings = graph.settings,
        profile = profile,
        nowMs = { System.currentTimeMillis() },
    )

    val installer = JvmUpdateInstaller(
        client = graph.http,
        updateDir = appDataDir.resolve("update"),
        installedCode = installedCode,
        scope = graph.scope,
        nowMs = { System.currentTimeMillis() },
    )

    /** The page's feed check: a verified newer candidate offers itself; anything else is informational. */
    suspend fun checkFeed(channel: UpdateChannel, forceRefresh: Boolean): UpdateFeedResult {
        val release = checker.latestRelease(channel, forceRefresh) ?: return UpdateFeedResult.UpToDate
        val offer = checker.eligibleOffer(release, channel) ?: return when (offerEligibility(release, channel)) {
            true -> UpdateFeedResult.NoInstallable
            false -> UpdateFeedResult.UpToDate
        }
        return UpdateFeedResult.Offer(offer)
    }

    /** true = a newer build exists but has no artifact this desktop can take (NoInstallable), false = not newer. */
    private fun offerEligibility(release: moe.rukamori.archivetune.shared.domain.update.ReleaseInfo, channel: UpdateChannel): Boolean =
        checker.eligibleOffer(release.copy(downloadUrl = release.htmlUrl), channel) != null

    suspend fun loadCommits(channel: UpdateChannel): List<UpdateCommit> =
        checker.commitHistory(COMMIT_HISTORY_COUNT).getOrThrow().map {
            UpdateCommit(it.sha, it.message, it.author, formatCommitDate(it.date), it.authorAvatarUrl, it.htmlUrl)
        }

    /** Release notes for the changelog page — the channel's own list, cached then network like the app. */
    suspend fun changelog(channel: UpdateChannel, forceRefresh: Boolean = false) =
        when (channel) {
            UpdateChannel.STABLE -> checker.allReleases(forceRefresh = forceRefresh)
            UpdateChannel.NIGHTLY -> checker.allPreReleases(forceRefresh = forceRefresh)
        }.map { releases ->
            releases.filter { it.tagName != moe.rukamori.archivetune.shared.domain.update.UpdateRepository.UPDATE_FEED_TAG }
                .map {
                    moe.rukamori.archivetune.shared.ui.settings.ChangelogRelease(
                        tag = it.tagName,
                        name = it.name ?: it.tagName,
                        publishedAt = it.publishedAt,
                        notes = it.body,
                    )
                }
        }

    /** Same label the app shows: ISO UTC parsed to `MMM d` in the host locale, raw prefix on failure. */
    private fun formatCommitDate(isoDate: String): String =
        try {
            val input = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            input.timeZone = java.util.TimeZone.getTimeZone("UTC")
            java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(input.parse(isoDate)!!)
        } catch (_: Exception) {
            isoDate.take(10)
        }

    fun install(offer: UpdateOffer) {
        when (installMode) {
            UpdateInstallMode.DIRECT_INSTALL -> installer.startDownload(offer)
            UpdateInstallMode.OPEN_URL -> openDesktopUri(offer.downloadUrl, graph::report)
            UpdateInstallMode.UNAVAILABLE -> Unit
        }
    }

    /**
     * The toggle commit lands first, then this runs. Android schedules a periodic worker here; the
     * desktop has no scheduler, so enabling performs one opportunistic check now and reports through
     * [DesktopSessionGraph.report] like the worker's Logcat-visible line.
     */
    suspend fun onNotificationPreferenceChanged(enabled: Boolean) {
        if (!enabled) return
        runCatching { checker.checkForNotification() }
            .onFailure { graph.report("update notification check failed") }
    }

    /** ReadyToInstall consumption: launch the platform installer/reveal, then let the owner go idle. */
    suspend fun consumeDownload(state: UpdateDownloadState) {
        when (state) {
            is UpdateDownloadState.ReadyToInstall -> {
                val outcome = installer.install(state.offer)
                if (outcome == UpdateInstallOutcome.REVEALED) {
                    graph.report("update installer not directly launchable; artifact folder revealed")
                }
                installer.acknowledgeResult()
            }
            is UpdateDownloadState.Failed -> installer.acknowledgeResult()
            else -> Unit
        }
    }

    private fun matchesDesktopArtifact(name: String): Boolean =
        name.startsWith("natune-desktop-$osToken-$archToken-") &&
            name.substringAfterLast('.').lowercase(Locale.US) in DESKTOP_UPDATE_EXTENSIONS

    private companion object {
        const val COMMIT_HISTORY_COUNT = 20
        val DESKTOP_UPDATE_EXTENSIONS = setOf("msi", "exe", "dmg", "pkg", "appimage", "deb", "rpm", "zip", "tar.gz", "gz")
    }
}
