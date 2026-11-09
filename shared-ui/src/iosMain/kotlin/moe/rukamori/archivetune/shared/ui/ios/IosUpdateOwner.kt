package moe.rukamori.archivetune.shared.ui.ios

import moe.rukamori.archivetune.shared.data.update.SharedUpdateChecker
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.UpdateEligibility
import moe.rukamori.archivetune.shared.domain.settings.updateEligibility
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallMode
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallProfile
import moe.rukamori.archivetune.shared.domain.update.branchForChannel
import moe.rukamori.archivetune.shared.domain.update.releaseBuildCode
import moe.rukamori.archivetune.shared.ui.settings.UpdateCommit
import moe.rukamori.archivetune.shared.ui.settings.UpdateFeedResult
import platform.Foundation.NSBundle

/** Real bundle identity and public release-page handoff; iOS never presents this as in-process IPA installation. */
internal class IosUpdateOwner(graph: IosSessionGraph) {
    private val info = NSBundle.mainBundle.infoDictionary
    private fun fact(key: String): String = (info?.get(key) as? String).orEmpty()

    val versionName = fact("CFBundleShortVersionString")
    val installedCode = fact("CFBundleVersion").toIntOrNull() ?: 0
    private val channel = fact("ArchiveTuneUpdateChannel").uppercase()
    val isNightlyBuild = channel == UpdateChannel.NIGHTLY.name
    val updaterAvailable = installedCode > 0 &&
        (channel == UpdateChannel.STABLE.name || channel == UpdateChannel.NIGHTLY.name)
    val installMode = if (updaterAvailable) UpdateInstallMode.OPEN_URL else UpdateInstallMode.UNAVAILABLE

    val checker = SharedUpdateChecker(
        client = graph.http,
        settings = graph.settings,
        profile = UpdateInstallProfile(
            updaterAvailable = updaterAvailable,
            installMode = installMode,
            isNightlyBuild = isNightlyBuild,
            installedCode = installedCode,
            versionName = versionName,
            artifactMatcher = { it.startsWith(IPA_PREFIX) && it.endsWith(IPA_SUFFIX) },
        ),
        nowMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun checkFeed(channel: UpdateChannel, forceRefresh: Boolean): UpdateFeedResult {
        val release = checker.latestRelease(channel, forceRefresh) ?: return UpdateFeedResult.UpToDate
        checker.eligibleOffer(release, channel)?.let { return UpdateFeedResult.Offer(it) }
        return when (updateEligibility(releaseBuildCode(release, channel), installedCode)) {
            UpdateEligibility.NEWER -> UpdateFeedResult.NoInstallable
            else -> UpdateFeedResult.UpToDate
        }
    }

    suspend fun loadCommits(channel: UpdateChannel): List<UpdateCommit> =
        checker.commitHistory(COMMIT_HISTORY_COUNT, branchForChannel(channel)).getOrThrow().map {
            UpdateCommit(it.sha, it.message, it.author, it.date.take(10), it.authorAvatarUrl, it.htmlUrl)
        }

    private companion object {
        const val COMMIT_HISTORY_COUNT = 20
        const val IPA_PREFIX = "natune-ios-"
        const val IPA_SUFFIX = ".ipa"
    }
}
