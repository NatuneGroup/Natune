package moe.rukamori.archivetune.shared.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel

class UpdateFeedContractTest {

    private fun manifestJson(channel: String = "nightly", versionCode: Long = 42, tag: String = "N20261027"): String =
        """
        {
          "schema": 1, "channel": "$channel", "tag": "$tag",
          "versionName": "$tag", "versionCode": $versionCode,
          "commit": "abc123", "publishedAt": "2026-10-27T00:00:00Z",
          "releaseUrl": "https://github.com/NatuneGroup/Natune/releases/tag/$tag",
          "notes": "nightly",
          "assets": [
            {"name": "app-gms-mobile-arm64-nightly.apk", "url": "https://example/a.apk", "size": 100, "sha256": "aa"},
            {"name": "natune-linux-x64-$tag.deb", "url": "https://example/a.deb"}
          ]
        }
        """.trimIndent()

    @Test
    fun manifestParsesExpectedChannelOnly() {
        val nightly = manifestJson(channel = "nightly")
        assertNotNull(parseUpdateFeedManifest(nightly, UpdateChannel.NIGHTLY))
        assertNull(parseUpdateFeedManifest(nightly, UpdateChannel.STABLE))
        assertNotNull(parseUpdateFeedManifest(manifestJson(channel = "stable", tag = "v1.0.0"), UpdateChannel.STABLE))
    }

    @Test
    fun manifestRejectsMalformedDocuments() {
        assertNull(parseUpdateFeedManifest("{", UpdateChannel.NIGHTLY))
        assertNull(parseUpdateFeedManifest(manifestJson().replace("\"schema\": 1", "\"schema\": 2"), UpdateChannel.NIGHTLY))
        assertNull(parseUpdateFeedManifest(manifestJson(versionCode = 0), UpdateChannel.NIGHTLY))
        assertNull(parseUpdateFeedManifest(manifestJson().replace("\"N20261027\"", "\"\""), UpdateChannel.NIGHTLY))
        assertNull(parseUpdateFeedManifest("[]", UpdateChannel.NIGHTLY))
    }

    @Test
    fun manifestChannelFieldIsLowercaseInsensitive() {
        val upper = manifestJson().replace("\"channel\": \"nightly\"", "\"channel\": \"NIGHTLY\"")
        assertNotNull(parseUpdateFeedManifest(upper, UpdateChannel.NIGHTLY))
    }

    @Test
    fun assetSelectionPrefersExactNameThenMatcher() {
        val manifest = parseUpdateFeedManifest(manifestJson(), UpdateChannel.NIGHTLY)!!
        val exact = selectUpdateFeedAssetUrl(manifest.assets, "app-gms-mobile-arm64-nightly.apk")
        assertEquals("https://example/a.apk", exact)
        val matched = selectUpdateFeedAssetUrl(manifest.assets, "other.apk") { it.endsWith(".deb") }
        assertEquals("https://example/a.deb", matched)
        assertNull(selectUpdateFeedAssetUrl(manifest.assets, "other.apk") { false })
    }

    @Test
    fun manifestMapsToReleaseInfoWithAssetAndTagFallback() {
        val manifest = parseUpdateFeedManifest(manifestJson(), UpdateChannel.NIGHTLY)!!
        val release = manifest.toReleaseInfo("https://example/a.apk", UpdateRepository.RELEASES_BASE_URL)
        assertEquals("N20261027", release.tagName)
        assertEquals(42L, release.versionCode)
        assertEquals("https://example/a.apk", release.downloadUrl)
        assertEquals("nightly", release.body)
        assertNull(release.sha256)
        // A blank releaseUrl falls back to the canonical tag URL.
        val noUrl = manifestJson().replace(",\n          \"releaseUrl\": \"https://github.com/NatuneGroup/Natune/releases/tag/N20261027\"", "")
        val fallback = parseUpdateFeedManifest(noUrl, UpdateChannel.NIGHTLY)!!
            .toReleaseInfo("https://example/a.apk", UpdateRepository.RELEASES_BASE_URL)
        assertEquals("${UpdateRepository.RELEASES_BASE_URL}/tag/N20261027", fallback.htmlUrl)
    }

    @Test
    fun selectedAssetDigestReachesReleaseInfoAndSurvivesTheCache() {
        val manifest = parseUpdateFeedManifest(manifestJson(), UpdateChannel.NIGHTLY)!!
        val asset = selectUpdateFeedAsset(manifest.assets, "app-gms-mobile-arm64-nightly.apk")
        assertEquals("https://example/a.apk", asset?.url)
        assertEquals("aa", asset?.sha256)
        val release = manifest.toReleaseInfo(asset?.url, UpdateRepository.RELEASES_BASE_URL, asset?.sha256)
        assertEquals("aa", release.sha256)
        // An asset the feed published without a digest makes no claim; nothing is invented.
        val undigested = selectUpdateFeedAsset(manifest.assets, "other.apk") { it.endsWith(".deb") }
        assertNull(undigested?.sha256)
        val undigestedRelease = manifest.toReleaseInfo(
            undigested?.url, UpdateRepository.RELEASES_BASE_URL, undigested?.sha256,
        )
        assertNull(undigestedRelease.sha256)
        // The cache round-trip keeps the digest so a restart does not lose verifiability.
        assertEquals("aa", parseReleasesJson(encodeReleasesJson(listOf(release)), "a.apk").single().sha256)
    }

    @Test
    fun releasesJsonRoundTripsThroughCacheEncoding() {
        val releases = listOf(
            ReleaseInfo("v1.2.3", "1.2.3", "notes", "2026-10-01T00:00:00Z", "https://x/tag", "https://x/a.apk", 7L),
            ReleaseInfo("N20261001", null, null, null, "https://x/tag2", null, null),
        )
        val encoded = encodeReleasesJson(releases)
        val decoded = parseReleasesJson(encoded, "a.apk")
        assertEquals(2, decoded.size)
        assertEquals("v1.2.3", decoded[0].tagName)
        assertEquals(7L, decoded[0].versionCode)
        // The cached download_url survives the round trip for manifest-derived entries.
        assertEquals("https://x/a.apk", decoded[0].downloadUrl)
        assertNull(decoded[1].downloadUrl)
    }

    @Test
    fun semVerOrdersReleasesAndPreReleases() {
        val a = parseSemVer("v1.2.3")!!
        val b = parseSemVer("1.2.4")!!
        val rc = parseSemVer("v1.2.4-rc1")!!
        assertTrue(a < b)
        assertTrue(rc < b) // a release outranks its own pre-release
        assertNull(parseSemVer("nightly"))
        assertNull(parseSemVer("1.2"))
    }

    @Test
    fun findLatestReleaseIgnoresPrereleaseTagsAndFeedTag() {
        val releases = listOf(
            ReleaseInfo("update-feed", null, null, "2026-10-20T00:00:00Z", "u", null, null),
            ReleaseInfo("N20261020", null, null, "2026-10-20T00:00:00Z", "u", null, null),
            ReleaseInfo("v1.0.0", null, null, "2026-10-01T00:00:00Z", "u", null, null),
            ReleaseInfo("v2.0.0", null, null, "2026-09-01T00:00:00Z", "u", null, null),
        )
        assertEquals("v2.0.0", findLatestRelease(releases)?.tagName)
    }

    @Test
    fun findLatestPreReleasePicksHighestNightlyPayload() {
        val releases = listOf(
            ReleaseInfo("update-feed", null, null, "2026-10-20T00:00:00Z", "u", null, null),
            ReleaseInfo("N20261001", null, null, "2026-10-01T00:00:00Z", "u", null, null),
            ReleaseInfo("N20261027", null, null, "2026-10-27T00:00:00Z", "u", null, null),
            ReleaseInfo("C20261028", null, null, "2026-10-28T00:00:00Z", "u", null, null), // retired canary: never nightly
            ReleaseInfo("v9.9.9", null, null, "2026-10-28T00:00:00Z", "u", null, null),
        )
        assertEquals("N20261027", findLatestPreRelease(releases, PreChannel.NIGHTLY)?.tagName)
    }

    @Test
    fun mergeManifestPrependsAndDedupsByTag() {
        val cached = listOf(
            ReleaseInfo("N20261026", null, null, null, "u1", null, null),
            ReleaseInfo("N20261027", null, null, null, "u2", null, null),
        )
        val manifest = ReleaseInfo("N20261027", null, null, null, "um", null, null)
        val merged = mergeManifestRelease(cached, manifest)
        assertEquals(listOf("N20261027", "N20261026"), merged.map { it.tagName })
        assertEquals("um", merged[0].htmlUrl)
        assertEquals(cached, mergeManifestRelease(cached, null))
    }
}
