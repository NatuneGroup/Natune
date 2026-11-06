package moe.rukamori.archivetune.shared.domain.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One GitHub release or prerelease the updater compares against the installed build. [versionCode] is the
 * build code the publisher attached (manifest `version_code` / body `at-build:` marker); [downloadUrl] is
 * the direct artifact link only when the release carries THIS build's artifact — the same semantics the
 * Android parser applies per expected artifact name.
 */
data class ReleaseInfo(
    val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val publishedAt: String? = null,
    val htmlUrl: String,
    val downloadUrl: String? = null,
    val versionCode: Long? = null,
)

/** One commit line of the update dashboard history. Mirrors the fields the app renders. */
data class GitCommit(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
    val htmlUrl: String,
    val authorAvatarUrl: String? = null,
)

/** The update repository and feed location. Both channels publish on the same repository. */
object UpdateRepository {
    const val OWNER: String = "NatuneGroup/Natune"

    /** The public project repository every About and support link opens. */
    const val REPOSITORY_URL: String = "https://github.com/$OWNER"
    const val RELEASES_BASE_URL: String = "https://github.com/$OWNER/releases"
    const val API_RELEASES_URL: String = "https://api.github.com/repos/$OWNER/releases"
    const val API_COMMITS_URL: String = "https://api.github.com/repos/$OWNER/commits"
    const val UPDATE_FEED_TAG: String = "update-feed"
    const val API_USER_AGENT: String = "ArchiveTune"
    const val API_ACCEPT: String = "application/vnd.github+json"
}

private val updateJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/**
 * Parse a releases JSON array — the GitHub API shape the cache stores verbatim. `version_code` is the
 * extra field the shared encoder writes for manifest-derived entries. [downloadUrl] is set only when an
 * asset matches [expectedArtifactName] exactly, then [artifactMatcher] — no match means the release page
 * is the only install path for this build.
 */
fun parseReleasesJson(
    text: String,
    expectedArtifactName: String? = null,
    artifactMatcher: ((String) -> Boolean)? = null,
): List<ReleaseInfo> {
    val array = runCatching { updateJson.parseToJsonElement(text).jsonArray }.getOrElse { return emptyList() }
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val tag = obj.string("tag_name") ?: return@mapNotNull null
        val htmlUrl = obj.string("html_url") ?: return@mapNotNull null
        var downloadUrl: String? = null
        (obj["assets"] as? JsonArray)?.forEach { assetEl ->
            val asset = assetEl as? JsonObject ?: return@forEach
            val name = asset.string("name") ?: return@forEach
            val url = asset.string("browser_download_url") ?: return@forEach
            if (expectedArtifactName != null && name == expectedArtifactName) downloadUrl = url
            else if (downloadUrl == null && artifactMatcher?.invoke(name) == true) downloadUrl = url
        }
        ReleaseInfo(
            tagName = tag,
            name = obj.string("name"),
            body = obj.string("body"),
            publishedAt = obj.string("published_at"),
            htmlUrl = htmlUrl,
            // A cached re-encode carries the resolved link outright; it survives the re-parse unchanged.
            downloadUrl = downloadUrl ?: obj.string("download_url"),
            versionCode = (obj["version_code"] as? JsonPrimitive)?.longOrNull,
        )
    }
}

/**
 * Re-encode a merged/cached list in the same API-shaped schema `parseReleasesJson` reads, so a cache
 * written from a manifest and one written from a raw API body round-trip identically.
 */
fun encodeReleasesJson(releases: List<ReleaseInfo>): String =
    buildJsonArray {
        releases.forEach { release ->
            add(buildJsonObject {
                put("tag_name", release.tagName)
                release.name?.let { put("name", it) }
                release.body?.let { put("body", it) }
                release.publishedAt?.let { put("published_at", it) }
                put("html_url", release.htmlUrl)
                release.versionCode?.let { put("version_code", it) }
                release.downloadUrl?.let { put("download_url", it) }
            })
        }
    }.toString()

/** Wire codec for the commit-history cache (keys are the cache schema, not the API's). */
fun encodeCommitHistory(commits: List<GitCommit>): String =
    buildJsonArray {
        commits.forEach { commit ->
            add(buildJsonObject {
                put("sha", commit.sha)
                put("message", commit.message)
                put("author", commit.author)
                put("date", commit.date)
                put("url", commit.htmlUrl)
                commit.authorAvatarUrl?.let { put("authorAvatarUrl", it) }
            })
        }
    }.toString()

fun parseCommitHistoryJson(text: String): List<GitCommit>? =
    runCatching {
        updateJson.parseToJsonElement(text).jsonArray.map { element ->
            val commit = element.jsonObject
            GitCommit(
                sha = commit["sha"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                message = commit["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                author = commit["author"]?.jsonPrimitive?.contentOrNull.orEmpty().ifEmpty { "Unknown" },
                date = commit["date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                htmlUrl = commit["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                authorAvatarUrl = commit["authorAvatarUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            )
        }
    }.getOrNull()

/**
 * Parse the GitHub `/commits` API shape: `sha` is shortened to 7, `commit.message` is cut to its first
 * line, author name/date come from `commit.author`, avatar from the top-level `author` user object.
 */
fun parseCommitsApiJson(text: String): List<GitCommit> {
    val array = runCatching { updateJson.parseToJsonElement(text).jsonArray }.getOrElse { return emptyList() }
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val commit = obj["commit"] as? JsonObject ?: return@mapNotNull null
        val author = commit["author"] as? JsonObject
        val githubAuthor = obj["author"] as? JsonObject
        GitCommit(
            sha = (obj.string("sha") ?: "").take(7),
            message = (commit.string("message") ?: "").lines().firstOrNull() ?: "",
            author = author?.string("name") ?: "Unknown",
            date = author?.string("date") ?: "",
            htmlUrl = obj.string("html_url") ?: "",
            authorAvatarUrl = githubAuthor?.string("avatar_url"),
        )
    }
}
