package moe.rukamori.archivetune.shared.domain.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel

const val UPDATE_FEED_MANIFEST_SCHEMA: Int = 1

/**
 * The update-feed manifest each channel publishes on the fixed `update-feed` release (`nightly.json`,
 * `stable.json`). CI emits schema 1; the client reads it defensively — any deviation yields null, not a
 * partial object.
 */
data class UpdateFeedAsset(val name: String, val url: String, val size: Long? = null, val sha256: String? = null)

data class UpdateFeedManifest(
    val channel: String,
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val commit: String?,
    val publishedAt: String?,
    val releaseUrl: String?,
    val notes: String?,
    val assets: List<UpdateFeedAsset>,
)

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

/**
 * Parse schema-1 manifest text. Returns null on malformed JSON, a schema other than 1, a channel that is
 * blank or not the expected one, a non-positive version code, or a blank tag — the caller falls back to the
 * API path rather than acting on a manifest it cannot trust. Unknown fields are ignored.
 */
fun parseUpdateFeedManifest(text: String, expectedChannel: UpdateChannel? = null): UpdateFeedManifest? {
    val root = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (failure: Throwable) {
        return null
    }
    if ((root["schema"] as? JsonPrimitive)?.intOrNull != UPDATE_FEED_MANIFEST_SCHEMA) return null
    val channel = root.string("channel") ?: return null
    // CI emits the channel lowercase (nightly/stable); compare case-insensitively like the app does.
    if (expectedChannel != null && channel.lowercase() != expectedChannel.name.lowercase()) return null
    val tag = root.string("tag") ?: return null
    val versionCode = root.long("versionCode") ?: return null
    if (versionCode <= 0) return null
    val assets = runCatching {
        root["assets"]?.jsonArray?.mapNotNull { entry ->
            val asset = entry as? JsonObject ?: return@mapNotNull null
            val name = asset.string("name") ?: return@mapNotNull null
            val url = asset.string("url") ?: return@mapNotNull null
            UpdateFeedAsset(name, url, asset.long("size"), asset.string("sha256"))
        }
    }.getOrNull() ?: emptyList()
    return UpdateFeedManifest(
        channel = channel,
        tag = tag,
        versionName = root.string("versionName") ?: tag,
        versionCode = versionCode,
        commit = root.string("commit"),
        publishedAt = root.string("publishedAt"),
        releaseUrl = root.string("releaseUrl"),
        notes = root.string("notes"),
        assets = assets,
    )
}

/**
 * Pick the asset a build can use. The Android host passes the exact artifact name it published
 * (`app-<dist>-<device>-<abi>-<suffix>.apk`); desktop matches its `natune-<platform>-<arch>-*` package.
 * An exact name match always wins over a predicate hit so a platform never grabs another's artifact.
 * The whole asset is returned so its `size`/`sha256` survive selection instead of being dropped.
 */
fun selectUpdateFeedAsset(
    assets: List<UpdateFeedAsset>,
    expectedArtifactName: String?,
    matches: ((String) -> Boolean)? = null,
): UpdateFeedAsset? {
    if (expectedArtifactName != null) {
        assets.firstOrNull { it.name == expectedArtifactName }?.let { return it }
        if (matches == null) return null
    }
    return matches?.let { predicate -> assets.firstOrNull { predicate(it.name) } }
}

/** URL-only selection, kept for callers that never consume the digest. */
fun selectUpdateFeedAssetUrl(
    assets: List<UpdateFeedAsset>,
    expectedArtifactName: String?,
    matches: ((String) -> Boolean)? = null,
): String? = selectUpdateFeedAsset(assets, expectedArtifactName, matches)?.url

/**
 * Fold a manifest into the release shape the checker already reasons about. The manifest's verified
 * `versionCode` is the build code; htmlUrl falls back to the tag page so the release page link always
 * resolves somewhere real. [sha256] is the selected asset's published digest, null when CI emitted none.
 */
fun UpdateFeedManifest.toReleaseInfo(
    assetUrl: String? = null,
    releasesBaseUrl: String = UpdateRepository.RELEASES_BASE_URL,
    sha256: String? = null,
): ReleaseInfo =
    ReleaseInfo(
        tagName = tag,
        name = versionName,
        body = notes,
        publishedAt = publishedAt,
        htmlUrl = releaseUrl?.takeIf { it.isNotBlank() } ?: "$releasesBaseUrl/tag/$tag",
        downloadUrl = assetUrl,
        versionCode = versionCode.coerceAtMost(Int.MAX_VALUE.toLong()),
        sha256 = sha256?.takeIf { it.isNotBlank() },
    )

/** Per-channel manifest file and feed release URL. */
fun updateFeedManifestUrl(channel: UpdateChannel, releasesBaseUrl: String = UpdateRepository.RELEASES_BASE_URL): String =
    "$releasesBaseUrl/download/${UpdateRepository.UPDATE_FEED_TAG}/${channel.name.lowercase()}.json"
