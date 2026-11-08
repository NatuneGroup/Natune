/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.modules

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.utils.isLocalMediaId
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ModuleMatchMetadata(
    val title: String,
    val artists: List<String>,
    val album: String?,
    val duration: Int,
    val explicit: Boolean?,
    val eligible: Boolean,
)

fun MediaMetadata.moduleMatchMetadata() = ModuleMatchMetadata(title, artists.map { it.name }, album?.title,
    duration, explicit.takeIf { it }, !id.isLocalMediaId() && !isPodcast && !isMusicVideo)

data class ModuleAudioSelection(
    val originalId: String,
    val module: InstalledModule,
    val trackId: String,
    val stream: ModuleStream,
    val cacheKey: String,
    val metadata: ModuleTrack? = null,
    val executionProvider: InstalledModule? = null,
    val executionConfiguration: Map<String, String> = emptyMap(),
)

enum class ModuleCheckStatus { READY, CONFIGURATION, UNAVAILABLE, FAILED, NO_MATCH, RESOLVED, TIMEOUT }

enum class ModuleResolutionPhase { SEARCH, MATCH, STREAM }
enum class ModuleMatchRejection { NO_RESULTS, METADATA, TITLE, ARTIST, RECORDING, DURATION, EXPLICIT }

data class ModuleDiagnostic(
    val status: ModuleCheckStatus,
    val atMs: Long,
    val elapsedMs: Long,
    val failure: ModuleFailure? = null,
    val revision: String? = null,
    val phase: ModuleResolutionPhase? = null,
    val candidateCount: Int = 0,
    val rejection: ModuleMatchRejection? = null,
    val upstream: String = "",
    val variant: String = "",
    val code: String = "",
)

data class ModuleHealth(val check: ModuleDiagnostic?, val playback: ModuleDiagnostic?)

@Singleton
class ModuleDiagnostics @Inject constructor() {
    private val mutableState = MutableStateFlow<Map<String, ModuleDiagnostic>>(emptyMap())
    private val mutableChecks = MutableStateFlow<Map<String, ModuleDiagnostic>>(emptyMap())
    val state = mutableState.asStateFlow()
    val checks = mutableChecks.asStateFlow()
    fun record(id: String, diagnostic: ModuleDiagnostic) {
        mutableState.update { it + (id to diagnostic) }
    }
    fun recordCheck(id: String, diagnostic: ModuleDiagnostic) {
        mutableChecks.update { it + (id to diagnostic) }
    }
}

class ModuleTrackMetadataRepository @Inject constructor(private val database: MusicDatabase) {
    suspend fun get(id: String): ModuleMatchMetadata? = withContext(Dispatchers.IO) {
        database.withTransaction {
            getSongByIdBlocking(id)?.let { item ->
                ModuleMatchMetadata(item.title, item.artists.map { it.name }, item.song.albumName, item.song.duration,
                    item.song.explicit.takeIf { it }, !item.song.isLocal && !item.song.isMusicVideo && podcastProgress.read(id) == null)
            }
        }
    }
}

@Singleton
class ResolveModuleAudioUseCase @Inject constructor(
    private val repository: ModuleRepository,
    private val metadataRepository: ModuleTrackMetadataRepository,
    private val diagnostics: ModuleDiagnostics,
) {
    private val mutex = Mutex()
    private data class Entry(val registryRevision: String, val moduleId: String, val moduleRevision: String, val metadata: ModuleMatchMetadata, val selection: ModuleAudioSelection?, val expiresAtMs: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    suspend fun resolveRegistry() { repository.snapshot() }

    val availability = repository.state.map { registry ->
        if (registry?.enabled != true) return@map false
        registry.modules.any { module ->
            if (moduleExecutionRoutes(registry, module).isEmpty()) false
            else if (!module.manifest.authentication) true
            else try {
                repository.runtime.checkAuthorization(module.id, module.source, module.origin)
                true
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { false }
        }
    }

    fun enabled(): Boolean = repository.state.value?.let { it.enabled && it.modules.any { module -> moduleExecutionRoutes(it, module).isNotEmpty() } } == true

    fun peek(id: String, metadata: ModuleMatchMetadata?): ModuleAudioSelection? {
        val registry = repository.state.value ?: return null
        if (!registry.enabled) return null
        for (module in registry.modules.filter { moduleExecutionRoutes(registry, it).isNotEmpty() }) {
            val entry = cache[cacheId(id, module.id)]?.takeIf {
                it.registryRevision == registry.revision && it.moduleRevision == module.revision && (metadata == null || it.metadata == metadata) && it.expiresAtMs > System.currentTimeMillis()
            } ?: return null
            if (entry.selection != null) return entry.selection
        }
        return null
    }

    suspend fun resolve(id: String, metadata: ModuleMatchMetadata? = null): ModuleAudioSelection? {
        val registry = try { repository.snapshot() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            diagnostics.record("registry", ModuleDiagnostic(ModuleCheckStatus.FAILED, System.currentTimeMillis(), 0, ModuleFailure.INVALID_CONFIGURATION))
            return null
        }
        if (!registry.enabled || registry.modules.none { moduleExecutionRoutes(registry, it).isNotEmpty() } || id.isLocalMediaId()) return null
        val target = metadata ?: metadataRepository.get(id) ?: return null
        if (!target.eligible || target.title.isBlank() || target.artists.none(String::isNotBlank)) return null
        return withTimeoutOrNull(30_000) {
            mutex.withLock {
                try {
                for (module in registry.modules.filter { moduleExecutionRoutes(registry, it).isNotEmpty() }) {
                    if (!stillCurrent(registry, module)) return@withTimeoutOrNull null
                    val key = cacheId(id, module.id)
                    val cached = cache[key]?.takeIf { it.registryRevision == registry.revision && it.moduleRevision == module.revision && it.metadata == target && it.expiresAtMs > System.currentTimeMillis() }
                    if (cached != null) {
                        if (cached.selection != null) return@withTimeoutOrNull cached.selection
                        continue
                    }
                    val started = System.currentTimeMillis()
                    var phase = ModuleResolutionPhase.SEARCH
                    var candidateCount = 0
                    try {
                        var completed = false
                        val candidate = withTimeoutOrNull(15_000) {
                            var selected: ModuleAudioSelection? = null
                            var routeFailure: Exception? = null
                            for (route in moduleExecutionRoutes(registry, module)) {
                                try {
                                    phase = ModuleResolutionPhase.SEARCH
                                    var routeCompleted = false
                                    val attempt = withTimeoutOrNull(7_000) {
                                        ModuleRepository.validateConfiguration(route.provider.manifest, route.provider.configuration)
                                        val tracks = repository.runtime.search(engineId(route.provider), route.provider.source, route.provider.origin,
                                            ModuleTrackMatcher.query(target), route.configuration)
                                        candidateCount += tracks.size
                                        phase = ModuleResolutionPhase.MATCH
                                        val evaluated = tracks.map { it to ModuleTrackMatcher.evaluate(target, it) }
                                        val match = evaluated.filter { it.second.rejection == null }.maxByOrNull { it.second.score }?.first
                                        routeCompleted = true
                                        if (match == null) {
                                            val rejection = evaluated.maxByOrNull { it.second.score }?.second?.rejection ?: ModuleMatchRejection.NO_RESULTS
                                            diagnostics.record(module.id, ModuleDiagnostic(ModuleCheckStatus.NO_MATCH, System.currentTimeMillis(),
                                                System.currentTimeMillis() - started, revision = module.revision, phase = phase,
                                                candidateCount = candidateCount, rejection = rejection))
                                            null
                                        } else {
                                            phase = ModuleResolutionPhase.STREAM
                                            selection(id, module, match.id, match, route)
                                        }
                                    }
                                    if (!routeCompleted) routeFailure = ModuleException(ModuleFailure.TIMEOUT)
                                    if (attempt != null) { selected = attempt; break }
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) { routeFailure = error }
                            }
                            if (selected == null && routeFailure != null) throw routeFailure
                            val result = selected
                            completed = true
                            result
                        }
                        if (!completed) diagnostics.record(module.id, ModuleDiagnostic(ModuleCheckStatus.TIMEOUT, System.currentTimeMillis(), System.currentTimeMillis() - started, ModuleFailure.TIMEOUT, module.revision, phase, candidateCount))
                        if (!stillCurrent(registry, module)) return@withTimeoutOrNull null
                        if (candidate != null) {
                            cache[key] = Entry(registry.revision, module.id, module.revision, target, candidate, candidate.stream.expiresAtMs - 60_000)
                            diagnostics.record(module.id, ModuleDiagnostic(ModuleCheckStatus.RESOLVED, System.currentTimeMillis(),
                                System.currentTimeMillis() - started, revision = module.revision, phase = phase, candidateCount = candidateCount,
                                upstream = candidate.stream.upstreamProvider, variant = candidate.stream.variant))
                            return@withTimeoutOrNull candidate
                        }
                        if (completed) cache[key] = Entry(registry.revision, module.id, module.revision, target, null, System.currentTimeMillis() + 30_000)
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        val failure = (error as? ModuleException)?.failure ?: ModuleFailure.EXECUTION
                        diagnostics.record(module.id, ModuleDiagnostic(when (failure) {
                            ModuleFailure.INVALID_CONFIGURATION -> ModuleCheckStatus.CONFIGURATION
                            ModuleFailure.NETWORK -> ModuleCheckStatus.UNAVAILABLE
                            ModuleFailure.TIMEOUT -> ModuleCheckStatus.TIMEOUT
                            else -> ModuleCheckStatus.FAILED
                        },
                            System.currentTimeMillis(), System.currentTimeMillis() - started, failure, module.revision, phase, candidateCount,
                            code = listOfNotNull((error as? ModuleException)?.stage?.name, (error as? ModuleException)?.diagnosticCode, error.javaClass.simpleName,
                                error.cause?.javaClass?.simpleName).joinToString("/")))
                        cache[key] = Entry(registry.revision, module.id, module.revision, target, null, System.currentTimeMillis() + 10_000)
                    }
                }
                    null
                } finally {
                    while (cache.size > 256) cache.keys.firstOrNull()?.let(cache::remove)
                }
            }
        }
    }

    suspend fun refresh(selection: ModuleAudioSelection): ModuleAudioSelection {
        if (selection.stream.expiresAtMs > System.currentTimeMillis() + 60_000) return selection
        val provider = selection.executionProvider ?: selection.module
        val registry = repository.snapshot()
        if (!registry.enabled || registry.modules.none { it.id == provider.id && it.revision == provider.revision && it.enabled } ||
            registry.modules.none { it.id == selection.module.id && it.revision == selection.module.revision && it.enabled })
            throw ModuleException(ModuleFailure.INVALID_CONFIGURATION)
        val route = ModuleExecution(provider, selection.executionConfiguration.ifEmpty { provider.configuration },
            moe.rukamori.archivetune.shared.domain.settings.ModuleAccountLane.OWN)
        val refreshed = this.selection(selection.originalId, selection.module, selection.trackId, selection.metadata, route)
        if (refreshed.cacheKey != selection.cacheKey ||
            (selection.stream.contentLength > 0 && refreshed.stream.contentLength > 0 && selection.stream.contentLength != refreshed.stream.contentLength)) {
            throw ModuleException(ModuleFailure.EXECUTION)
        }
        return refreshed
    }

    private suspend fun selection(id: String, module: InstalledModule, trackId: String, metadata: ModuleTrack?, execution: ModuleExecution): ModuleAudioSelection {
        val provider = execution.provider
        val stream = repository.runtime.stream(engineId(provider), provider.source, provider.origin, trackId, execution.configuration, metadata)
        val identity = listOf(id, module.id, module.revision, provider.id, provider.revision, trackId, stream.upstreamProvider, stream.upstreamId, stream.variant, stream.mimeType, stream.codecs,
            stream.bitrate.toString(), stream.sampleRate?.toString().orEmpty()).joinToString("") { "${it.length}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return ModuleAudioSelection(id, module, trackId, stream, "module:$digest", metadata, provider, execution.configuration)
    }

    fun invalidateFailures(moduleId: String) {
        cache.entries.removeIf { it.value.selection == null && it.value.moduleId == moduleId }
    }

    private fun engineId(module: InstalledModule) = "${module.id}:${module.revision}"
    private fun cacheId(id: String, moduleId: String) = "${id.length}:$id$moduleId"

    private fun stillCurrent(expected: ModuleRegistry, module: InstalledModule): Boolean {
        val current = repository.state.value ?: return false
        if (!current.enabled || current.revision != expected.revision) return false
        val preceding = expected.modules.filter { moduleExecutionRoutes(expected, it).isNotEmpty() }.takeWhile { it.id != module.id } + module
        return current.modules.filter { moduleExecutionRoutes(current, it).isNotEmpty() }.take(preceding.size).map { it.id to it.revision } == preceding.map { it.id to it.revision }
    }
}

/** Adapts a matched module stream to Natune's existing source chain without changing song identity. */
fun ModuleAudioSelection.directStream() = moe.rukamori.archivetune.audiosource.DirectStream(
    uri = stream.url, mimeType = stream.mimeType, codecs = stream.codecs,
    contentLength = stream.contentLength.takeIf { it > 0 }, label = module.manifest.name,
    source = moe.rukamori.archivetune.constants.AudioSourceType.MODULES,
    matchedTitle = metadata?.title, matchedArtist = metadata?.artists?.joinToString(", "),
    matchedAlbum = metadata?.album, matchedDurationMs = metadata?.durationSeconds?.toLong()?.times(1000),
    sampleRate = stream.sampleRate, expiresAtEpochMs = stream.expiresAtMs,
)
