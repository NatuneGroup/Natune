package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.modules.*
import moe.rukamori.archivetune.desktop.integration.DesktopLaunchIntake
import moe.rukamori.archivetune.desktop.integration.auth.DesktopBrowserAuth
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.data.lyrics.LyricsProvider
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.*
import org.json.JSONObject

/** The retained API-v1 archive/QuickJS host, with desktop installation ownership and vault-backed settings. */
internal class DesktopModules(private val root: Path, private val preferences: () -> PreferenceSnapshot?,
    private val admissions: moe.rukamori.archivetune.desktop.provider.DesktopSourceAdmissions) : AudioProviderRepository {
    data class Entry(val id: String, val source: String, val origin: String, val manifest: ModuleManifest, val enabled: Boolean)
    override val source = AudioSourceType.MODULES
    private val environment = ModuleHostEnvironment()
    private val http = ModuleHttpClient(okhttp3.OkHttpClient.Builder().proxySelector(java.net.ProxySelector.getDefault()).build())
    private val runtime = ModuleRuntime(environment, http)
    private val gate = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    val entries = MutableStateFlow<List<Entry>>(emptyList())

    suspend fun load() = withContext(Dispatchers.IO) { gate.withLock {
        Files.createDirectories(root)
        val loaded = ArrayList<Entry>()
        val paths = Files.list(root).use { files -> files.filter { it.fileName.toString().endsWith(".json") }.limit(64).toList() }
        for (file in paths) {
            if (Files.size(file) > 2 * 1024 * 1024) continue
            val value = JSONObject(Files.readString(file))
            val id = file.fileName.toString().removeSuffix(".json")
            if (runCatching { UUID.fromString(id) }.isFailure) continue
            val script = value.getString("source")
            val origin = value.getString("origin")
            val manifest = runtime.inspect(script, origin)
            manifest.requireCompatible(ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION)
            loaded += Entry(id, script, origin, manifest, value.optBoolean("enabled"))
        }
        entries.value = loaded
    } }

    suspend fun install(bytes: ByteArray, origin: String = "https://modules.invalid/", updateId: String? = null): Entry {
        val script = ModulePackageReader.read(bytes, ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION)
        val manifest = runtime.inspect(script, origin)
        manifest.requireCompatible(ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION)
        return gate.withLock {
            val previous = entries.value.firstOrNull { if (updateId != null) it.id == updateId else it.manifest.id == manifest.id }
            require(updateId == null || previous != null) { "Module update installation is missing" }
            if (previous != null) require(previous.manifest.id == manifest.id) { "Module update identity changed" }
            require(previous != null || entries.value.size < 64) { "Module installation limit reached" }
            val entry = Entry(previous?.id ?: UUID.randomUUID().toString(), script, origin, manifest, false)
            persist(entry)
            generation.incrementAndGet()
            previous?.let { runtime.unload(it.id) }
            entries.value = entries.value.filterNot { it.id == entry.id } + entry
            entry
        }
    }
    suspend fun installUrl(url: String, digest: String? = null, updateId: String? = null): Entry {
        val (bytes, origin) = http.source(url)
        if (!digest.isNullOrBlank()) require(ModuleRuntimeStorage.hashBytes(bytes) == digest.lowercase()) { "Module package digest mismatch" }
        return install(bytes, origin, updateId)
    }
    suspend fun catalog(url: String) = ModuleCatalogReader(http).read(url)
    suspend fun enable(id: String, enabled: Boolean) = gate.withLock {
        val entry = entries.value.first { it.id == id }.copy(enabled = enabled)
        persist(entry)
        generation.incrementAndGet()
        if (!enabled) runtime.unload(id)
        entries.value = entries.value.map { if (it.id == id) entry else it }
    }
    suspend fun remove(id: String) = gate.withLock {
        val entry = entries.value.firstOrNull { it.id == id } ?: return@withLock
        runtime.forget(entry.id)
        generation.incrementAndGet()
        Files.deleteIfExists(root.resolve("${entry.id}.json"))
        entries.value = entries.value.filterNot { it.id == entry.id }
    }
    suspend fun settings(entry: Entry): Map<String, String> {
        val saved = withContext(Dispatchers.IO) { ModuleRuntimeStorage(environment, entry.id).read("settings") }
        return entry.manifest.settings.associate { field -> field.key to saved.optString(field.key, field.defaultJson) }
    }
    suspend fun saveSettings(entry: Entry, values: Map<String, String>) = withContext(Dispatchers.IO) { gate.withLock {
        requireCurrent(entry)
        require(values.keys == entry.manifest.settings.map { it.key }.toSet() &&
            entry.manifest.settings.all { it.acceptsValue(values.getValue(it.key)) }) { "Invalid module settings" }
        ModuleRuntimeStorage(environment, entry.id).write("settings", JSONObject(values))
        generation.incrementAndGet()
        runtime.unload(entry.id)
    } }
    suspend fun checkAuthorization(entry: Entry): Boolean {
        requireCurrent(entry)
        return try { runtime.checkAuthorization(entry.id, entry.source, entry.origin); true }
        catch (failure: ModuleException) {
            if (failure.failure == ModuleFailure.INVALID_CONFIGURATION) false else throw failure
        }
    }
    suspend fun authorize(entry: Entry) {
        requireCurrent(entry)
        val url = runtime.authorize(entry.id, entry.source, entry.origin)
        if (url != null) {
            val state = withContext(Dispatchers.IO) { ModuleRuntimeStorage(environment, entry.id).read("authorization").getString("state") }
            val browser = DesktopBrowserAuth(DesktopLaunchIntake.links)
            val callback = withTimeout(180_000L) { browser.authorizeModuleSession(BrowserAuthRequest(
                "module-${entry.id}", CredentialScope.PERSONAL, url, ModuleSignedSession.CALLBACK, state)) }
            gate.withLock {
                requireCurrent(entry)
                check(runtime.completeAuthorization(entry.id, entry.source, entry.origin, callback.callbackUri)) { "Module grant was declined" }
            }
        }
        gate.withLock { requireCurrent(entry); generation.incrementAndGet(); runtime.unload(entry.id) }
        check(checkAuthorization(entry)) { "Module verification is required" }
    }
    suspend fun clearAuthorization(entry: Entry) = withContext(Dispatchers.IO) { gate.withLock {
        requireCurrent(entry)
        ModuleRuntimeStorage(environment, entry.id).clearAuthorization()
        generation.incrementAndGet()
        runtime.unload(entry.id)
    } }
    private fun requireCurrent(entry: Entry) {
        check(entries.value.any { it.id == entry.id && it.source == entry.source }) { "Module installation changed" }
    }
    private suspend fun persist(entry: Entry) = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, ".module-", ".part")
        try {
            Files.writeString(temporary, JSONObject().put("source", entry.source).put("origin", entry.origin)
                .put("enabled", entry.enabled).toString())
            Files.move(temporary, root.resolve("${entry.id}.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        if (preferences()?.get(ModulesEnabledKey, false) != true) return null
        for (entry in entries.value.filter { it.enabled && it.manifest.providesAudio }) {
            val captured = generation.get()
            val settings = settings(entry)
            val matches = runtime.search(entry.id, entry.source, entry.origin,
                (listOf(request.target.title) + request.target.artists.take(1)).joinToString(" "), settings)
            val best = TrackMatching.best(request.target, matches.map {
                TrackMatching.Candidate(it.id, it.title, it.artists, it.album, it.durationSeconds?.times(1000L))
            }) ?: continue
            val track = matches.first { it.id == best.id }
            val stream = runtime.stream(entry.id, entry.source, entry.origin, track.id, settings, track)
            if (entries.value.none { it.id == entry.id && it.enabled && it.source == entry.source }) return null
            // Header-bearing progressive streams are already supported by the ordinary host route.
            if (stream.mimeType in setOf("application/dash+xml", "application/x-mpegURL")) continue
            val admission = admissions.accept(moe.rukamori.archivetune.shared.domain.platform.ProviderIdentity("modules",
                moe.rukamori.archivetune.shared.domain.platform.CredentialScope.PERSONAL, entry.id), captured) {
                generation.get() == captured && preferences()?.get(ModulesEnabledKey, false) == true &&
                    entries.value.any { it.id == entry.id && it.enabled && it.source == entry.source }
            } ?: return null
            return PreparedAudio.Progressive(DirectStream(stream.url, stream.mimeType, stream.codecs,
                stream.contentLength.takeIf { it > 0 }, entry.manifest.name, source, track.title, track.artists.joinToString(", "),
                track.album, track.durationSeconds?.times(1000L), headers = PlaybackHeaders.of(stream.headers),
                accountScope = admission.scope, expiresAtEpochMs = stream.expiresAtMs))
        }
        return null
    }
    val lyrics = object : LyricsProvider {
        override val name = "Modules"
        override suspend fun getLyrics(id: String, title: String, artist: String, album: String?, durationSec: Int, isrc: String?): Result<String> =
            try {
                val entry = entries.value.firstOrNull { it.enabled && it.manifest.kind == ModuleKind.LYRICS } ?: error("No module lyric provider enabled")
                val result = runtime.lyrics(entry.id, entry.source, entry.origin, ModuleTrack(id, title, listOf(artist), album, durationSec, null, isrc), settings(entry))
                    ?: error("Module lyrics unavailable")
                Result.success(result.text)
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Result.failure(failure) }
    }
    suspend fun close() { entries.value.forEach { runtime.unload(it.id) } }
}
