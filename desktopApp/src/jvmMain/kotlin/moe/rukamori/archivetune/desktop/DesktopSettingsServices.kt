package moe.rukamori.archivetune.desktop

import androidx.compose.ui.text.font.FontFamily
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.Inflater
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import moe.rukamori.archivetune.desktop.storage.DesktopStorageRelocation
import moe.rukamori.archivetune.shared.data.ai.KtorAiTextService
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapanesePackFailure
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseLanguagePackManager
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseLanguagePackState
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapanesePackIo
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.modules.providesAudio
import moe.rukamori.archivetune.shared.ui.settings.AddonDownloadUiState
import moe.rukamori.archivetune.shared.ui.settings.AiSettingsActions
import moe.rukamori.archivetune.shared.ui.settings.ChangelogRelease
import moe.rukamori.archivetune.shared.ui.settings.JapaneseLanguagePackUiState
import moe.rukamori.archivetune.shared.ui.settings.PoTokenGeneration
import moe.rukamori.archivetune.shared.ui.settings.PoTokenHelperStatus
import moe.rukamori.archivetune.shared.ui.settings.ProxyConnectionResult
import moe.rukamori.archivetune.shared.ui.settings.SettingsCacheKind
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SourcePoolRefreshOutcome
import moe.rukamori.archivetune.shared.ui.settings.UpdateCommit
import moe.rukamori.archivetune.shared.ui.settings.UpdateFeedResult
import moe.rukamori.archivetune.shared.ui.settings.YtDlpRuntimeStatus

/** One manifest asset as this legacy desktop check reads it; the digest is carried, never dropped. */
private data class DesktopFeedAsset(val name: String, val url: String, val sha256: String?)

/**
 * The host behaviours behind the settings pages the desktop mounts. Every action here does real
 * work against the real world — network probes hit the same endpoints the Android host uses, file
 * work happens inside the live instance directory, and nothing reports a fabricated result.
 */
internal class DesktopSettingsServices(
    private val graph: DesktopSessionGraph,
    private val controller: SettingsController,
    val instanceDirectory: Path,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val plainClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    val aiActions by lazy {
        AiSettingsActions(graph.scope, controller, KtorAiTextService(graph.http, graph.aiRateLimiter))
    }

    // ------------------------------------------------------------------ updates

    suspend fun checkUpdateFeed(channel: UpdateChannel): UpdateFeedResult = withContext(Dispatchers.IO) {
        val url = "$UPDATE_FEED_BASE/${channel.name.lowercase()}.json"
        val text = httpGet(url) ?: return@withContext UpdateFeedResult.Unavailable
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return@withContext UpdateFeedResult.Unavailable
        if (root["schema"]?.jsonPrimitive?.intOrNull != UPDATE_FEED_MANIFEST_SCHEMA ||
            root["channel"]?.jsonPrimitive?.content != channel.name.lowercase()
        ) return@withContext UpdateFeedResult.Unavailable
        val tag = root["tag"]?.jsonPrimitive?.content ?: return@withContext UpdateFeedResult.Unavailable
        val versionCode = root["versionCode"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0
        if (versionCode <= 0) return@withContext UpdateFeedResult.Unavailable
        val versionName = root["versionName"]?.jsonPrimitive?.content ?: tag
        val assets = root["assets"]?.jsonArray.orEmpty().mapNotNull { asset ->
            val obj = runCatching { asset.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val assetUrl = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            DesktopFeedAsset(name, assetUrl, obj["sha256"]?.jsonPrimitive?.content)
        }
        val asset = selectDesktopAsset(assets)
            ?: return@withContext UpdateFeedResult.NoInstallable
        // Desktop packages carry no integer build code; only the tag equality check can prove the
        // running build is already the published one, so an unverifiable code reports 0 and any
        // feed entry strictly above it is a real, non-downgrade offer.
        val installedCode = DesktopBuildIdentity.VERSION_NAME.toIntOrNull() ?: 0
        val offer = eligibleUpdateOffer(
            channel = channel,
            tag = tag,
            buildCode = versionCode,
            versionName = versionName,
            notes = root["notes"]?.jsonPrimitive?.content,
            downloadUrl = asset.url.takeIf { it.startsWith("http") },
            installedCode = installedCode,
            sha256 = asset.sha256,
        )
        when {
            tag == DesktopBuildIdentity.VERSION_NAME -> UpdateFeedResult.UpToDate
            offer != null -> UpdateFeedResult.Offer(offer)
            else -> UpdateFeedResult.UpToDate
        }
    }

    /** Desktop manifests carry platform-named assets; pick the one for this OS, else null. */
    private fun selectDesktopAsset(assets: List<DesktopFeedAsset>): DesktopFeedAsset? {
        val os = System.getProperty("os.name").lowercase()
        val markers = when {
            "mac" in os || "darwin" in os -> listOf("macos", "darwin", "dmg", "mac")
            "win" in os -> listOf("windows", "win", "msi", "exe")
            else -> listOf("linux", "appimage", "deb", "rpm")
        }
        return assets.firstOrNull { (name, _, _) ->
            val lower = name.lowercase()
            markers.any { lower.contains(it) }
        }
    }

    suspend fun loadUpdateCommits(channel: UpdateChannel): List<UpdateCommit> = withContext(Dispatchers.IO) {
        val branch = if (channel == UpdateChannel.NIGHTLY) "dev" else "main"
        val text = httpGet(
            "https://api.github.com/repos/NatuneGroup/Natune/commits?sha=$branch&per_page=30",
            "Accept" to "application/vnd.github+json",
        ) ?: return@withContext emptyList()
        runCatching {
            json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val sha = obj["sha"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val commit = obj["commit"]?.jsonObject
                val message = commit?.get("message")?.jsonPrimitive?.content.orEmpty().lineSequence().firstOrNull().orEmpty()
                val authorName = commit?.get("author")?.jsonObject?.get("name")?.jsonPrimitive?.content
                    ?: obj["author"]?.jsonObject?.get("login")?.jsonPrimitive?.content.orEmpty()
                val date = commit?.get("author")?.jsonObject?.get("date")?.jsonPrimitive?.content.orEmpty()
                UpdateCommit(
                    sha = sha,
                    message = message,
                    author = authorName,
                    dateLabel = date.take(10),
                    avatarUrl = obj["author"]?.jsonObject?.get("avatar_url")?.jsonPrimitive?.content,
                    url = obj["html_url"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchChangelog(channel: UpdateChannel): List<ChangelogRelease> = withContext(Dispatchers.IO) {
        val text = httpGet(
            "https://api.github.com/repos/NatuneGroup/Natune/releases?per_page=30",
            "Accept" to "application/vnd.github+json",
        ) ?: error("Release feed unreachable")
        val parsed = runCatching {
            json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val prerelease = obj["prerelease"]?.jsonPrimitive?.content == "true"
                val nightly = tag.startsWith("N") || prerelease
                val wanted = channel == UpdateChannel.NIGHTLY
                if (nightly != wanted) return@mapNotNull null
                ChangelogRelease(
                    tag = tag,
                    name = obj["name"]?.jsonPrimitive?.content ?: tag,
                    publishedAt = obj["published_at"]?.jsonPrimitive?.content.orEmpty(),
                    notes = obj["body"]?.jsonPrimitive?.content,
                )
            }
        }.getOrElse { throw it }
        parsed.ifEmpty { error("No $channel releases published") }
    }

    // ------------------------------------------------------------------ storage

    private val cacheDirectories = linkedMapOf(
        SettingsCacheKind.SONG to "cache/songs",
        SettingsCacheKind.IMAGE to "cache/images",
        SettingsCacheKind.CANVAS to "cache/canvas",
        SettingsCacheKind.LYRICS to "cache/lyrics",
    )

    /** Real on-disk sizes; a kind with no backing directory is omitted, never reported as zero. */
    suspend fun usedCacheBytes(): Map<SettingsCacheKind, Long> = withContext(Dispatchers.IO) {
        buildMap {
            for ((kind, relative) in cacheDirectories) {
                val dir = instanceDirectory.resolve(relative)
                if (Files.isDirectory(dir)) put(kind, directoryBytes(dir))
            }
        }
    }

    suspend fun clearCache(kind: SettingsCacheKind): Unit = withContext(Dispatchers.IO) {
        if (kind == SettingsCacheKind.SONG) graph.songCache.clear()
        val dir = instanceDirectory.resolve(requireNotNull(cacheDirectories[kind]))
        if (Files.isDirectory(dir)) {
            Files.list(dir).use { stream ->
                stream.forEach { child -> child.toFile().deleteRecursively() }
            }
        }
    }

    suspend fun applyCacheLimits(preferences: PreferenceSnapshot): Unit = withContext(Dispatchers.IO) {
        graph.songCache.applyLimit(preferences[MaxSongCacheSizeKey, -1])
        trimToLimit(instanceDirectory.resolve(cacheDirectories.getValue(SettingsCacheKind.IMAGE)), preferences[MaxImageCacheSizeKey, -1])
        trimToLimit(instanceDirectory.resolve(cacheDirectories.getValue(SettingsCacheKind.CANVAS)), preferences[MaxCanvasCacheSizeKey, -1])
    }

    /** Trims oldest entries first; a negative or blank limit means unlimited. */
    private fun trimToLimit(dir: Path, limitMb: Int) {
        if (limitMb <= 0 || !Files.isDirectory(dir)) return
        val limitBytes = limitMb.toLong() * 1024 * 1024
        val entries = Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .sorted { a, b -> Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)) }
                .toList()
        }
        var total = entries.sumOf { Files.size(it) }
        for (entry in entries) {
            if (total <= limitBytes) break
            val size = Files.size(entry)
            if (Files.deleteIfExists(entry)) total -= size
        }
    }

    suspend fun canvasCacheBytes(): Long? = withContext(Dispatchers.IO) {
        val dir = instanceDirectory.resolve(cacheDirectories.getValue(SettingsCacheKind.CANVAS))
        if (Files.isDirectory(dir)) directoryBytes(dir) else null
    }

    suspend fun applyCanvasCacheLimit(limitMb: Int): Unit = withContext(Dispatchers.IO) {
        trimToLimit(instanceDirectory.resolve(cacheDirectories.getValue(SettingsCacheKind.CANVAS)), limitMb)
    }

    suspend fun clearCanvasCache(): Unit = clearCache(SettingsCacheKind.CANVAS)

    private fun directoryBytes(dir: Path): Long =
        Files.walk(dir).use { stream -> stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }

    /**
     * Records a move to a user-picked folder for the next start. Nothing is copied while the graph has the database
     * open: checkpoints, settings and downloads written after this point stay in the current directory and are part of
     * the snapshot the next start copies before the session opens. The returned token is the destination; null means
     * the user cancelled.
     */
    suspend fun pickAndCommitStorage(): String? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose storage location"
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext null
        val target = chooser.selectedFile.toPath()
            .resolve(instanceDirectory.fileName.toString())
            .toAbsolutePath()
            .normalize()
        if (target == instanceDirectory.toAbsolutePath().normalize()) return@withContext target.toString()
        DesktopStorageRelocation.stage(instanceDirectory, target)
        target.toString()
    }

    companion object {
        private const val UPDATE_FEED_BASE =
            "https://github.com/NatuneGroup/Natune/releases/download/update-feed"
        private const val UPDATE_RELEASES_BASE = "https://github.com/NatuneGroup/Natune/releases"
        private const val UPDATE_FEED_MANIFEST_SCHEMA = 1

        /** Fixed pointer file the next launch reads before opening the instance directory. */
        fun storagePointerFile(): Path = DesktopStorageRelocation.pointerFile()

        fun resolveStorageDirectory(fallback: Path): Path = DesktopStorageRelocation.resolveStorageDirectory(fallback)

        /**
         * The instance directory this launch honours: an explicit override, else the relocated pointer, else default.
         * A staged move is applied here, before the single-instance lock and the graph exist.
         */
        fun currentInstanceDirectory(): Path = DesktopStorageRelocation.currentInstanceDirectory()
    }

    // ------------------------------------------------------------------ network

    fun activeRotationCount(): Int = graph.network.activeRotationCount()

    suspend fun applyNetworkSettings(preferences: PreferenceSnapshot) = graph.network.applyNetworkSettings(preferences)

    suspend fun testProxy(preferences: PreferenceSnapshot): ProxyConnectionResult = graph.network.testProxy(preferences)

    suspend fun applyIpRotation(enabled: Boolean, refresh: Boolean): Int = graph.network.applyIpRotation(enabled, refresh)

    // ------------------------------------------------------------------ sources

    /** Real per-source reachability, on the same endpoints the original provider code talks to. */
    suspend fun checkSource(type: AudioSourceType, preferences: PreferenceSnapshot): Boolean =
        withContext(Dispatchers.IO) {
            when (type) {
                AudioSourceType.TIDAL -> preferences.tidalInstanceList().firstOrNull()
                    ?.let { probeReachable(it.trimEnd('/') + "/health", fallback = it) } ?: false
                AudioSourceType.QOBUZ -> probeReachable("https://www.qobuz.com/api.json/0.2/status")
                AudioSourceType.QOBUZ_BACKUP -> probeReachable("https://kouzu.in/")
                AudioSourceType.DEEZER -> probeReachable("https://api.deezer.com/infos")
                AudioSourceType.APPLE -> probeReachable("https://amp-api.music.apple.com/v1/catalog/us/genres")
                AudioSourceType.AMAZON -> preferences.amazonInstanceList().firstOrNull()
                    ?.let { probeReachable(it) } ?: false
                AudioSourceType.MODULES -> graph.modules.entries.value.any { it.enabled && it.manifest.providesAudio }
                AudioSourceType.QQ -> probeReachable("https://c.y.qq.com/")
                AudioSourceType.SPOTIFY ->
                    preferences.spotifyStreamSessionPresent(preferences.spotifyStreamEngineEffective())
                AudioSourceType.JIOSAAVN -> probeReachable("https://www.jiosaavn.com/api.php?__call=webapi.getLaunchData&_format=json&_marker=0")
                AudioSourceType.YOUTUBE -> probeReachable(PROXY_PROBE_URL)
            }
        }

    suspend fun refreshPool(): SourcePoolRefreshOutcome = withContext(Dispatchers.IO) {
        runCatching {
            if (!graph.playbackPool.configured) return@withContext SourcePoolRefreshOutcome.FAILED
            val accounts = graph.playbackPool.tidalAccounts()
            if (accounts.isEmpty()) SourcePoolRefreshOutcome.FAILED else SourcePoolRefreshOutcome.REFRESHED
        }.getOrDefault(SourcePoolRefreshOutcome.FAILED)
    }

    private fun probeReachable(url: String, fallback: String? = null): Boolean {
        val target = if (url.startsWith("http")) url else "https://$url"
        return runCatching {
            val request = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(6)).GET().build()
            plainClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..399
        }.getOrElse {
            if (fallback != null) {
                runCatching {
                    val req = HttpRequest.newBuilder(URI.create(if (fallback.startsWith("http")) fallback else "https://$fallback"))
                        .timeout(Duration.ofSeconds(6)).GET().build()
                    plainClient.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..599
                }.getOrDefault(false)
            } else false
        }
    }

    // ------------------------------------------------------------------ canvas

    val canvasProbes = MutableStateFlow(CanvasProviderProbes())

    suspend fun canvasConnectivity(): CanvasConnectivity =
        withContext(Dispatchers.IO) {
            CanvasConnectivity(
                online = probeReachable("https://music.youtube.com/generate_204"),
                // The JVM reachability probe measures neither transport nor metering.
                wifi = false,
                metered = true,
            )
        }

    suspend fun refreshCanvasHealth(providers: Set<CanvasHealthProvider>) {
        val endpoints = mapOf(
            CanvasHealthProvider.BETTER_LYRICS to "https://better-lyrics-api.boidu.dev/",
            CanvasHealthProvider.APPLE_MUSIC to "https://amp-api.music.apple.com/v1/catalog/us/genres",
            CanvasHealthProvider.TIDAL to "https://api.tidal.com/",
            CanvasHealthProvider.SPOTIFY to "https://open.spotify.com/",
        )
        coroutineScope {
            val checks = providers.mapNotNull { provider ->
                val endpoint = endpoints[provider] ?: return@mapNotNull null
                provider to endpoint
            }
            canvasProbes.value = checks.fold(canvasProbes.value) { acc, (provider, _) ->
                acc.withState(provider, CanvasProbeState.CHECKING)
            }
            checks.map { (provider, endpoint) ->
                async {
                    val reachable = probeReachable(endpoint)
                    provider to if (reachable) CanvasProbeState.AVAILABLE else CanvasProbeState.UNAVAILABLE
                }
            }.awaitAll().forEach { (provider, state) ->
                canvasProbes.value = canvasProbes.value.withState(provider, state)
            }
        }
    }

    private fun CanvasProviderProbes.withState(provider: CanvasHealthProvider, state: CanvasProbeState) =
        when (provider) {
            CanvasHealthProvider.BETTER_LYRICS -> copy(betterLyrics = state)
            CanvasHealthProvider.APPLE_MUSIC -> copy(appleMusic = state)
            CanvasHealthProvider.TIDAL -> copy(tidal = state)
            CanvasHealthProvider.SPOTIFY -> copy(spotify = state)
        }

    // ------------------------------------------------------------------ qobuz

    val qobuzTokenHealth = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())
    val qobuzInstanceHealth = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())

    suspend fun checkQobuzTokens(tokens: List<QobuzTokenEntry>, probeTrack: String?, quality: String?) {
        coroutineScope {
            tokens.map { entry ->
                async {
                    val start = System.nanoTime()
                    val healthy = probeQobuzToken(entry)
                    val latency = (System.nanoTime() - start) / 1_000_000
                    entry to SourceHealth(
                        if (healthy) SourceHealthStatus.HEALTHY else SourceHealthStatus.UNREACHABLE,
                        latency,
                    )
                }
            }.awaitAll().forEach { (entry, health) ->
                qobuzTokenHealth.value = qobuzTokenHealth.value + (entry.token to health)
            }
        }
    }

    /** The same user/getUserMembership call the Android provider uses to prove a token. */
    private fun probeQobuzToken(entry: QobuzTokenEntry): Boolean = runCatching {
        val uri = URI.create(
            "https://www.qobuz.com/api.json/0.2/user/getUserMembership" +
                "?app_id=${entry.appId}&user_id=${entry.userId}&user_auth_token=${entry.token}",
        )
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
            .header("X-App-Id", entry.appId).header("X-User-Auth-Token", entry.token).GET().build()
        plainClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    suspend fun checkQobuzInstances(instances: List<String>, probeTrack: String?, quality: String?) {
        coroutineScope {
            instances.map { instance ->
                async {
                    val start = System.nanoTime()
                    val base = instance.trim().trimEnd('/')
                    val reachable = probeReachable("$base/api.json/0.2/status") || probeReachable(base)
                    val latency = (System.nanoTime() - start) / 1_000_000
                    instance to SourceHealth(
                        if (reachable) SourceHealthStatus.HEALTHY else SourceHealthStatus.UNREACHABLE,
                        latency,
                    )
                }
            }.awaitAll().forEach { (instance, health) ->
                qobuzInstanceHealth.value = qobuzInstanceHealth.value + (instance to health)
            }
        }
    }

    fun forgetQobuzTokenHealth(token: String) {
        qobuzTokenHealth.value = qobuzTokenHealth.value - token
    }

    fun forgetQobuzInstanceHealth(instance: String) {
        qobuzInstanceHealth.value = qobuzInstanceHealth.value - instance
    }

    // ------------------------------------------------------------------ language packs

    val japanesePackManager by lazy {
        JapaneseLanguagePackManager(
            graph.scope,
            Dispatchers.IO,
            controller.store.snapshots,
            { change -> controller.edit(change) },
            DesktopJapanesePackIo(instanceDirectory),
            { java.util.Locale.getDefault().country.takeIf { it.isNotBlank() } },
            { probeReachable("https://repo.maven.apache.org/") },
        )
    }

    suspend fun installJapanese(): Unit = run { japanesePackManager.install()?.join() }
    suspend fun cancelJapanese(): Unit = japanesePackManager.cancel()
    suspend fun removeJapanese(): Unit = run { japanesePackManager.remove()?.join() }

    fun mapJapaneseState(state: JapaneseLanguagePackState): JapaneseLanguagePackUiState = when (state) {
        is JapaneseLanguagePackState.Checking -> JapaneseLanguagePackUiState.Checking
        is JapaneseLanguagePackState.NotInstalled -> JapaneseLanguagePackUiState.NotInstalled
        is JapaneseLanguagePackState.Downloading -> JapaneseLanguagePackUiState.Downloading(state.progressPercent)
        is JapaneseLanguagePackState.Removing -> JapaneseLanguagePackUiState.Removing
        is JapaneseLanguagePackState.Installed -> JapaneseLanguagePackUiState.Installed(state.sizeBytes)
        is JapaneseLanguagePackState.Failed -> JapaneseLanguagePackUiState.Failed(
            when (state.reason) {
                JapanesePackFailure.INVALID -> "Downloaded dictionary failed validation"
                JapanesePackFailure.INSTALL -> "Dictionary install failed"
                JapanesePackFailure.REMOVE -> "Dictionary removal failed"
            },
            state.removalFailed,
        )
    }

    /**
     * The optional SpatialFlow typeface. The owner keeps the installed file inside the instance directory and hands the
     * player its family, so the title is drawn in it once the file is installed.
     */
    val spatialFlowFontOwner by lazy { DesktopSpatialFlowFont(instanceDirectory, graph.scope) }
    val spatialFlowFont: StateFlow<AddonDownloadUiState> get() = spatialFlowFontOwner.state
    val spatialFlowFontFamily: StateFlow<FontFamily?> get() = spatialFlowFontOwner.family
    suspend fun installSpatialFlowFont(): Boolean = spatialFlowFontOwner.installPicked()
    suspend fun removeSpatialFlowFont(): Boolean = spatialFlowFontOwner.remove()

    // ------------------------------------------------------------------ yt-dlp / downloads / po / amazon

    suspend fun readYtDlpRuntime(): YtDlpRuntimeStatus = withContext(Dispatchers.IO) {
        YtDlpRuntimeStatus(
            pythonAvailable = commandRuns("python3", "--version") || commandRuns("python", "--version"),
            scriptAvailable = commandRuns("yt-dlp", "--version"),
        )
    }

    private fun commandRuns(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command).redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    suspend fun clearDownloads(): Unit = withContext(Dispatchers.IO) {
        listOf("downloads", "download").forEach { name ->
            val dir = instanceDirectory.resolve(name)
            if (Files.isDirectory(dir)) dir.toFile().deleteRecursively()
        }
    }

    /**
     * PO token minting runs the real BotGuard recipe through the shared QuickJS engine — the same
     * owner the resolver mints from. [sourceUrl] is the Android WebView destination and stays
     * informational on this host; unconfigured credentials or a refused challenge surface as an
     * honest failure rather than fabricated tokens.
     */
    suspend fun generatePoTokens(sourceUrl: String): PoTokenGeneration =
        graph.poTokens.generate(sourceUrl, controller.store.snapshots.value)

    /**
     * Reports the managed helper's lifecycle for the PO token settings row. It reads the current
     * state only; opening the page never downloads or starts the helper.
     */
    suspend fun poTokenProviderStatus(): PoTokenHelperStatus = graph.poTokens.localHelperStatus()

    private suspend fun httpGet(url: String, vararg headers: Pair<String, String>): String? =
        runCatching {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15)).GET()
            headers.forEach { (k, v) -> builder.header(k, v) }
            val response = plainClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) response.body() else null
        }.getOrNull()

    // ------------------------------------------------------------------ japanese pack io

    private class DesktopJapanesePackIo(instanceDirectory: Path) : JapanesePackIo {
        private val directory: Path = instanceDirectory.resolve("language_packs")
        private val installed: Path = directory.resolve(PACK_FILE)
        private val partial: Path = directory.resolve("$PACK_FILE.partial")

        override suspend fun readInstalled(): ByteArray? = read(installed)
        override suspend fun readStaged(): ByteArray? = read(partial)

        private suspend fun read(path: Path): ByteArray? = withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(path)) return@withContext null
            val bytes = Files.readAllBytes(path)
            require(bytes.size <= ARCHIVE_SIZE) { "Dictionary exceeds size limit" }
            bytes
        }

        override suspend fun prepare(): Unit = withContext(Dispatchers.IO) {
            Files.createDirectories(directory)
            val free = Files.getFileStore(directory).usableSpace
            check(free >= ARCHIVE_SIZE * 2L + 4L * 1024 * 1024) { "Insufficient dictionary storage" }
            discardStaged()
        }

        override suspend fun download(progress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
            val request = HttpRequest.newBuilder(URI.create(PACK_URL))
                .timeout(Duration.ofMinutes(5)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() == 200) { "Dictionary download failed (${response.statusCode()})" }
            val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            var received = 0L
            Files.newOutputStream(partial).use { out ->
                response.body().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        received += read
                        check(received <= ARCHIVE_SIZE) { "Dictionary exceeds size limit" }
                        out.write(buffer, 0, read)
                        progress(received, total)
                    }
                }
            }
        }

        override suspend fun promote(): Unit = withContext(Dispatchers.IO) {
            Files.move(partial, installed, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        override suspend fun discardStaged(): Unit = withContext(Dispatchers.IO) {
            Files.deleteIfExists(partial)
        }

        override suspend fun removeInstalled(): Unit = withContext(Dispatchers.IO) {
            Files.deleteIfExists(installed)
        }

        /** The jar ships zlib (raw deflate) members; inflate exactly expectedSize bytes. */
        override suspend fun inflateRaw(compressed: ByteArray, expectedSize: Int): ByteArray =
            withContext(Dispatchers.IO) {
                require(expectedSize in 1..9_500_000) { "Invalid dictionary member size" }
                val inflater = Inflater(true)
                try {
                    inflater.setInput(compressed)
                    val result = ByteArray(expectedSize)
                    var offset = 0
                    while (offset < expectedSize) {
                        val written = inflater.inflate(result, offset, expectedSize - offset)
                        check(written > 0 || inflater.finished()) { "Invalid dictionary compression" }
                        if (inflater.finished()) break
                        offset += written
                    }
                    check(offset == expectedSize) { "Dictionary inflation size mismatch" }
                    result
                } finally {
                    inflater.end()
                }
            }

        private companion object {
            private const val PACK_FILE = "kuromoji-ipadic-0.9.0.jar"
            private const val PACK_URL =
                "https://repo.maven.apache.org/maven2/com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar"
            private const val ARCHIVE_SIZE = 20L * 1024 * 1024
        }
    }

    // ------------------------------------------------------------------ misc

    fun formatExpiry(millis: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
}
