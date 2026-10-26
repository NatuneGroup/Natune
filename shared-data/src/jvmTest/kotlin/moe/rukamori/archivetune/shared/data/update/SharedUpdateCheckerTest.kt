package moe.rukamori.archivetune.shared.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.settings.GitHubRateLimitedUntilKey
import moe.rukamori.archivetune.shared.domain.settings.GitHubReleasesJsonKey
import moe.rukamori.archivetune.shared.domain.settings.GitHubReleasesLastCheckedAtKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsChange
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannelKey
import moe.rukamori.archivetune.shared.domain.settings.UpdateFeedManifestStableJsonKey
import moe.rukamori.archivetune.shared.domain.settings.UpdateFeedManifestStableLastCheckedAtKey
import moe.rukamori.archivetune.shared.domain.update.ReleaseInfo
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallMode
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallProfile
import moe.rukamori.archivetune.shared.domain.update.encodeReleasesJson

private class FakeSettingsStore(initial: PreferenceSnapshot = PreferenceSnapshot.EMPTY) : SettingsStore {
    private val state = MutableStateFlow<PreferenceSnapshot?>(initial)
    override val snapshots: StateFlow<PreferenceSnapshot?> = state

    override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
        val before = state.value ?: PreferenceSnapshot.EMPTY
        val editor = before.toEditor()
        editor.change()
        val after = editor.snapshot()
        state.value = after
        return SettingsChange(before, after)
    }

    fun current(): PreferenceSnapshot = state.value ?: PreferenceSnapshot.EMPTY
}

/**
 * Mock-engine coverage of the fetch order the app follows: TTL cache → channel manifest on the fixed
 * `update-feed` release → rate-limit backoff → ETag'd releases API → fingerprint-gated cache writes.
 */
class SharedUpdateCheckerTest {

    private fun stableProfile(installedCode: Int = 10) = UpdateInstallProfile(
        updaterAvailable = true,
        installMode = UpdateInstallMode.DIRECT_INSTALL,
        isNightlyBuild = false,
        installedCode = installedCode,
        versionName = "1.0.0",
        artifactNameFor = { null },
        artifactMatcher = { it.startsWith("natune-linux-x64-") && it.endsWith(".deb") },
    )

    private fun stableManifest(versionCode: Long = 11, tag: String = "v2.0.0") = """
        {
          "schema": 1, "channel": "stable", "tag": "$tag",
          "versionName": "$tag", "versionCode": $versionCode,
          "commit": "abc123", "publishedAt": "2026-10-27T00:00:00Z",
          "releaseUrl": "", "notes": "stable notes",
          "assets": [{"name": "natune-linux-x64-$tag.deb", "url": "https://example/$tag.deb"}]
        }
    """.trimIndent()

    private fun apiReleasesJson(tag: String, body: String, assetName: String) = """
        [{"tag_name": "$tag", "name": "$tag", "body": "$body", "html_url": "https://example/tag/$tag",
          "published_at": "2026-10-27T00:00:00Z", "prerelease": false,
          "assets": [{"name": "$assetName", "browser_download_url": "https://example/$assetName"}]}]
    """.trimIndent()

    private fun checker(
        store: FakeSettingsStore,
        respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        nowMs: () -> Long = { 1_000_000L },
        profile: UpdateInstallProfile = stableProfile(),
    ): Pair<SharedUpdateChecker, HttpClient> {
        val client = HttpClient(MockEngine(respond))
        return SharedUpdateChecker(client, store, profile, nowMs) to client
    }

    @Test
    fun manifestHitAnswersCheckAndCachesWithoutReleasesApi() = runTest {
        val store = FakeSettingsStore()
        val apiCalls = mutableListOf<String>()
        val (checker, client) = checker(store, { request ->
            if (request.url.host == "api.github.com") apiCalls += request.url.encodedPath
            when {
                request.url.encodedPath.endsWith("update-feed/stable.json") -> respond(stableManifest())
                else -> respond("[]")
            }
        })
        try {
            val releases = checker.allReleases(forceRefresh = true).getOrThrow()
            assertEquals(listOf("v2.0.0"), releases.map { it.tagName })
            assertEquals(0, apiCalls.size)
            // The manifest is cached raw and merged into the release cache the same write.
            assertTrue(store.current()[GitHubReleasesJsonKey]!!.contains("v2.0.0"))
            assertEquals(1_000_000L, store.current()[GitHubReleasesLastCheckedAtKey])
            assertNotNull(store.current()[UpdateFeedManifestStableJsonKey])
            assertEquals(1_000_000L, store.current()[UpdateFeedManifestStableLastCheckedAtKey])
            // The offered artifact is the direct download link for a DIRECT_INSTALL build.
            val release = checker.latestRelease(UpdateChannel.STABLE)
            assertEquals("v2.0.0", release?.tagName)
            assertEquals("https://example/v2.0.0.deb", checker.cachedOffer(UpdateChannel.STABLE)?.downloadUrl)
        } finally { client.close() }
    }

    @Test
    fun equalOrLowerManifestCodeIsNeverAnOffer() = runTest {
        val store = FakeSettingsStore()
        val (checker, client) = checker(
            store,
            { respond(stableManifest(versionCode = 10)) },
            profile = stableProfile(installedCode = 10),
        )
        try {
            assertNotNull(checker.latestRelease(UpdateChannel.STABLE))
            assertNull(checker.cachedOffer(UpdateChannel.STABLE))
        } finally { client.close() }
    }

    @Test
    fun manifestMissFallsBackToEtagReleasesApi() = runTest {
        val store = FakeSettingsStore()
        var apiRequests = 0
        val (checker, client) = checker(store, { request ->
            when {
                request.url.encodedPath.endsWith("update-feed/stable.json") ->
                    respond("not found", HttpStatusCode.NotFound)
                request.url.host == "api.github.com" -> {
                    apiRequests += 1
                    respond(
                        apiReleasesJson("v2.0.0", "at-build:11", "natune-linux-x64-2.0.0.deb"),
                        headers = headersOf("ETag", "\"new-etag\""),
                    )
                }
                else -> respond("[]")
            }
        })
        try {
            val releases = checker.allReleases(forceRefresh = true).getOrThrow()
            assertEquals(listOf("v2.0.0"), releases.map { it.tagName })
            assertEquals(1, apiRequests)
            // body marker at-build:11 makes the API release eligible over installedCode 10
            val release = checker.latestRelease(UpdateChannel.STABLE)
            assertEquals("v2.0.0", release?.tagName)
            assertEquals("STABLE|v2.0.0|11", checker.cachedOffer(UpdateChannel.STABLE)?.identity)
        } finally { client.close() }
    }

    @Test
    fun rateLimitBackoffSkipsApiAndServesCache() = runTest {
        val now = 1_000_000L
        val cached = encodeReleasesJson(
            listOf(ReleaseInfo("v1.0.0", "1.0.0", "at-build:9", null, "https://example/tag/v1.0.0", null, 9L)),
        )
        val store = FakeSettingsStore(
            PreferenceSnapshot.EMPTY.toEditor()
                .also {
                    it[GitHubReleasesJsonKey] = cached
                    it[GitHubRateLimitedUntilKey] = now + 60_000L
                }.snapshot(),
        )
        val apiCalls = mutableListOf<String>()
        val (checker, client) = checker(store, { request ->
            if (request.url.host == "api.github.com") apiCalls += request.url.encodedPath
            respond("not found", HttpStatusCode.NotFound)
        }, nowMs = { now })
        try {
            val releases = checker.allReleases(forceRefresh = true).getOrThrow()
            assertEquals(listOf("v1.0.0"), releases.map { it.tagName })
            assertEquals(0, apiCalls.size)
        } finally { client.close() }
    }

    @Test
    fun freshManifestCacheAvoidsNetworkEntirely() = runTest {
        val now = 1_000_000L
        val store = FakeSettingsStore(
            PreferenceSnapshot.EMPTY.toEditor()
                .also {
                    it[UpdateFeedManifestStableJsonKey] = stableManifest()
                    it[UpdateFeedManifestStableLastCheckedAtKey] = now
                }.snapshot(),
        )
        var requests = 0
        val (checker, client) = checker(store, { requests += 1; respond("[]") }, nowMs = { now })
        try {
            val releases = checker.allReleases().getOrThrow()
            assertEquals(listOf("v2.0.0"), releases.map { it.tagName })
            assertEquals(0, requests)
        } finally { client.close() }
    }

    @Test
    fun channelSelectionReadsStoreAndWriteBumpsRevision() = runTest {
        val store = FakeSettingsStore(
            PreferenceSnapshot.EMPTY.toEditor().also { it[UpdateChannelKey] = "NIGHTLY" }.snapshot(),
        )
        val (checker, client) = checker(store, { respond("[]") })
        try {
            assertEquals(UpdateChannel.NIGHTLY, checker.selectedChannel())
            val revision = checker.selectionRevision
            checker.selectChannel(UpdateChannel.NIGHTLY) // already selected: no write, no bump
            assertEquals(revision, checker.selectionRevision)
            checker.selectChannel(UpdateChannel.STABLE)
            assertEquals(revision + 1, checker.selectionRevision)
            assertEquals("STABLE", store.current()[UpdateChannelKey])
        } finally { client.close() }
    }

    @Test
    fun nightlyManifestFeedsPrereleaseChannel() = runTest {
        val store = FakeSettingsStore()
        val (checker, client) = checker(
            store,
            { request ->
                when {
                    request.url.encodedPath.endsWith("update-feed/nightly.json") -> respond(
                        stableManifest(versionCode = 11, tag = "N20261027")
                            .replace("\"channel\": \"stable\"", "\"channel\": \"nightly\""),
                    )
                    else -> respond("[]")
                }
            },
            profile = stableProfile().copy(isNightlyBuild = true, versionName = "N20261026"),
        )
        try {
            val releases = checker.allPreReleases(forceRefresh = true).getOrThrow()
            assertEquals(listOf("N20261027"), releases.map { it.tagName })
            val release = checker.latestRelease(UpdateChannel.NIGHTLY)
            assertEquals("N20261027", release?.tagName)
            assertEquals("NIGHTLY|N20261027|11", checker.cachedOffer(UpdateChannel.NIGHTLY)?.identity)
        } finally { client.close() }
    }
}
