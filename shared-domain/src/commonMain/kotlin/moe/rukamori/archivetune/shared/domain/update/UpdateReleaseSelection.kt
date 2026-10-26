package moe.rukamori.archivetune.shared.domain.update

import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel

/**
 * Stable/Nightly selection over the GitHub release list, ported exactly from the Android updater:
 * - Stable tags are `vX.Y.Z` semver (optionally on the release name instead of the tag); `N…`/`C…`
 *   pre-release tags and the fixed `update-feed` tag are filtered out before comparison.
 * - Nightly tags are `N<yyyyMMdd>` optionally followed by `<HHmm>`; the lexicographic digit payload
 *   orders them so the newest build wins.
 */

data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        // A bare release is newer than any of its pre-releases; otherwise compare identifiers.
        if (preRelease == null && other.preRelease == null) return 0
        if (preRelease == null) return 1
        if (other.preRelease == null) return -1
        val mine = preRelease.split('.')
        val theirs = other.preRelease.split('.')
        val size = minOf(mine.size, theirs.size)
        for (index in 0 until size) {
            val a = mine[index]
            val b = theirs[index]
            if (a == b) continue
            val aNum = a.toIntOrNull()
            val bNum = b.toIntOrNull()
            if (aNum != null && bNum != null) return aNum.compareTo(bNum)
            // Numeric identifiers rank below alphanumeric ones (semver §11).
            if (aNum != null) return -1
            if (bNum != null) return 1
            return a.compareTo(b)
        }
        return mine.size.compareTo(theirs.size)
    }

    override fun toString(): String = "$major.$minor.$patch${preRelease?.let { "-$it" } ?: ""}"
}

private val semVerRegex = Regex("""(?i)\bv?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?\b""")

fun parseSemVer(text: String): SemVer? = semVerRegex.find(text)?.destructured?.let { (maj, min, patch, pre) ->
    SemVer(maj.toInt(), min.toInt(), patch.toInt(), pre.ifBlank { null })
}

/** Channel identity for the pre-release feed: tag prefix, APK suffix, source branch. */
enum class PreChannel(val artifactSuffix: String, val tagPrefix: String, val branch: String) {
    NIGHTLY("nightly", "N", "dev"),
    ;

    val tagRegex: Regex get() = Regex("$tagPrefix\\d{8}(?:\\d{4})?")
}

val preReleaseTagRegex: Regex = Regex("[NC]\\d{8}(?:\\d{4})?")

val buildNumberRegex: Regex = Regex("\\(build (\\d+)\\)")

/** `at-build:N` publisher marker in release notes — the verified build code when present. */
val nightlyBuildMarkerRegex: Regex = Regex("at-build:(\\d+)")

fun branchForChannel(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.STABLE -> "main"
    UpdateChannel.NIGHTLY -> PreChannel.NIGHTLY.branch
}

/**
 * Version-code tie-break for the FLOOR+epoch_minutes allocation: two builds packaged inside the same
 * minute carry the same code. Eligibility stays strictly greater, so a client already on the first
 * build is never re-offered the second — equal code is never a downgrade and never a re-prompt. Feed
 * ordering prefers the later tag/publishedAt so the newest build is what a manual check surfaces.
 * See docs/engineering/update-feed-contract.md.
 */
fun buildNumberOrNull(releaseName: String?): Int? =
    releaseName?.let { buildNumberRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }

/** Nightly build code: manifest/version_code wins, then the `at-build:` marker, then the tag's semver patch. */
fun nightlyBuildNumber(release: ReleaseInfo): Int? =
    release.versionCode?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        ?: release.body?.let { nightlyBuildMarkerRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: release.name?.let { parseSemVer(it)?.patch }

/** Build code a channel assigns to a release: Stable reads the same `at-build:`/version_code sources. */
fun releaseBuildCode(release: ReleaseInfo, channel: UpdateChannel): Int? = when (channel) {
    UpdateChannel.STABLE -> release.versionCode?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        ?: release.body?.let { nightlyBuildMarkerRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    UpdateChannel.NIGHTLY -> nightlyBuildNumber(release)
}

/** Display name for the newest release: normalized semver name, then raw name, then the tag. */
fun getReleaseVersionName(release: ReleaseInfo): String {
    val normalized = release.name?.let { parseSemVer(it)?.toString() }
    val base = normalized ?: release.name ?: release.tagName
    val code = releaseBuildCode(release, UpdateChannel.STABLE)
    return if (code != null) "$base (build $code)" else base
}

fun getPreReleaseVersionName(release: ReleaseInfo, installedVersionName: String): String =
    nightlyBuildNumber(release)?.let { "$installedVersionName (build $it)" } ?: release.tagName

/**
 * Newest stable release: `N…`/`C…`/`update-feed` tags excluded; semver taken from tag first, then name;
 * releases with a parseable semver beat unparseable ones; ties break on publishedAt.
 */
fun findLatestRelease(releases: List<ReleaseInfo>): ReleaseInfo? {
    val nonPreRelease = releases.filter { !preReleaseTagRegex.matches(it.tagName) && it.tagName != UpdateRepository.UPDATE_FEED_TAG }
    val parsed = nonPreRelease.mapNotNull { release ->
        val semver = parseSemVer(release.tagName) ?: release.name?.let { parseSemVer(it) }
        semver?.let { release to it }
    }
    return parsed.maxWithOrNull(compareBy({ it.second }, { it.first.publishedAt ?: "" }))?.first
        ?: nonPreRelease.firstOrNull()
}

/** Newest prerelease for a channel: only its own tag pattern, ordered by the tag's digit payload. */
fun findLatestPreRelease(releases: List<ReleaseInfo>, channel: PreChannel): ReleaseInfo? =
    releases.filter { channel.tagRegex.matches(it.tagName) }
        .maxByOrNull { it.tagName.removePrefix(channel.tagPrefix).takeWhile(Char::isDigit).toLongOrNull() ?: 0L }

/**
 * A manifest hit replaces the cached entry for the same tag and otherwise prepends itself — the feed
 * stays fresh between API polls without dropping entries the manifest does not mention.
 */
fun mergeManifestRelease(cached: List<ReleaseInfo>, manifest: ReleaseInfo?): List<ReleaseInfo> =
    if (manifest == null) cached else listOf(manifest) + cached.filter { it.tagName != manifest.tagName }

/** Cheap change markers for cache writes; the list is only rewritten when the winner actually changed. */
fun topReleaseFingerprint(releases: List<ReleaseInfo>): String? = findLatestRelease(releases)?.tagName

fun preReleaseTopFingerprint(releases: List<ReleaseInfo>, channel: PreChannel): String? =
    findLatestPreRelease(releases, channel)?.tagName

/** Artifact name a release publishes for a distribution/device/arch, as the Android build computes it. */
fun stableReleaseArtifactName(releaseArtifactPrefix: String, device: String, architecture: String): String =
    "app-$releaseArtifactPrefix$device-$architecture-release.apk"

fun preReleaseArtifactName(releaseArtifactPrefix: String, device: String, architecture: String, channel: PreChannel): String =
    "app-$releaseArtifactPrefix$device-$architecture-${channel.artifactSuffix}.apk"
