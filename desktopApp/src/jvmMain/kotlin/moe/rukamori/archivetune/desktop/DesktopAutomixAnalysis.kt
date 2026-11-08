package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.desktop.playback.EngineBackend
import moe.rukamori.archivetune.desktop.playback.EngineOutputMode
import moe.rukamori.archivetune.desktop.playback.NativePlaybackEngine
import moe.rukamori.archivetune.shared.domain.automix.TrackAnalysis
import moe.rukamori.archivetune.shared.domain.automix.TrackFeatures
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.AutomixEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/** Opt-in full-file analysis reuses the shared compact beat/vocal heuristics; no models or network fetches. */
internal class DesktopAutomixAnalysis(private val scope: CoroutineScope, private val preferences: () -> PreferenceSnapshot?) {
    var fileLookup: suspend (ContentItem) -> Path? = { null }
    private data class Result(val path: Path, val size: Long, val modified: Long, val features: TrackAnalysis)
    private val completed = LinkedHashMap<ContentRef, Result>()
    private val selected = LinkedHashMap<String, Path>()
    private val pending = HashSet<ContentRef>()
    private val worker = Mutex()
    val revision = MutableStateFlow(0L)
    fun sourceSelected(occurrence: ContentOccurrence, file: Path?) = synchronized(completed) {
        selected.remove(occurrence.occurrenceId)
        file?.let { selected[occurrence.occurrenceId] = it.toAbsolutePath().normalize() }
        while (selected.size > 64) selected.remove(selected.keys.first())
        if (file != null) consider(occurrence.item)
    }
    fun consider(item: ContentItem) {
        if (preferences()?.get(AutomixEnabledKey, false) != true || item.ref.kind != ContentKind.SONG || (item.durationMs ?: 0) > 600_000) return
        synchronized(pending) { if (!pending.add(item.ref)) return }
        scope.launch(Dispatchers.IO) {
            try {
                worker.withLock {
                    if (preferences()?.get(AutomixEnabledKey, false) != true) return@withLock
                    val file = fileLookup(item)?.toAbsolutePath()?.normalize() ?: return@withLock
                    val size = Files.size(file)
                    val modified = Files.getLastModifiedTime(file).toMillis()
                    if (synchronized(completed) { completed[item.ref]?.let { it.path == file && it.size == size && it.modified == modified } } == true) return@withLock
                    val features = withTimeout(30_000L) { decode(file, item) } ?: return@withLock
                    if (!Files.isRegularFile(file) || Files.size(file) != size || Files.getLastModifiedTime(file).toMillis() != modified) return@withLock
                    synchronized(completed) {
                        completed[item.ref] = Result(file, size, modified, features)
                        while (completed.size > 32) completed.remove(completed.keys.first())
                    }
                    revision.value++
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { desktopDiagnostic("Automix analysis declined: ${failure.javaClass.simpleName}") }
            finally { synchronized(pending) { pending.remove(item.ref) } }
        }
    }
    fun features(occurrence: ContentOccurrence, requireSelected: Boolean): TrackAnalysis? = synchronized(completed) {
        val result = completed[occurrence.item.ref] ?: return@synchronized null
        if (requireSelected && selected[occurrence.occurrenceId] != result.path) return@synchronized null
        if (!Files.isRegularFile(result.path) || Files.size(result.path) != result.size || Files.getLastModifiedTime(result.path).toMillis() != result.modified)
            return@synchronized null
        result.features
    }
    private suspend fun decode(file: Path, item: ContentItem): TrackAnalysis? = coroutineScope {
        val engine = NativePlaybackEngine.create(EngineOutputMode.MANUAL, EngineBackend.NULL, sampleRate = 11_025)
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { delay(30_000L) } finally { engine.stop() }
        }
        try {
            engine.load(file.toString(), 0, true)
            val samples = FloatArray(11_025 * 600)
            val block = FloatArray(4096 * 2)
            var count = 0
            val deadline = System.nanoTime() + 30_000_000_000L
            while (count < samples.size && System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                val frames = engine.render(block, minOf(4096, samples.size - count))
                for (index in 0 until frames) samples[count++] = (block[index * 2] + block[index * 2 + 1]) * 0.5f
                val state = engine.snapshot()
                if (state.errorCode != 0 || state.durationUs > 600_000_000L) return@coroutineScope null
                if (state.isEnded) break
                if (frames == 0) delay(2)
            }
            if (!engine.snapshot().isEnded || count < 11_025 * 16) return@coroutineScope null
            val context = currentCoroutineContext()
            val features = TrackFeatures.analyze(samples, count / 11_025.0, checkActive = { context.ensureActive() }) ?: return@coroutineScope null
            TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = item.ref.id, duration = features.duration,
                bpm = features.bpm, beatInterval = features.beatInterval, beatConfidence = features.beatConfidence,
                downbeats = features.downbeats, phraseBoundaries = features.phraseBoundaries, firstBeat = features.firstBeat,
                key = features.key, keyConfidence = features.keyConfidence, audibleStartTime = features.audibleStartTime,
                pickupTime = features.pickupTime, introEndTime = features.introEndTime, outroStartTime = features.outroStartTime,
                contentEndTime = features.contentEndTime, finalFadeOnsetTime = features.finalFadeOnsetTime,
                mixInTime = features.mixInTime, mixOutTime = features.mixOutTime, mixInCandidates = features.mixInCandidates,
                mixOutCandidates = features.mixOutCandidates, energyCurve = features.energyCurve, lowEnergyCurve = features.lowEnergyCurve,
                vocalActivityMask = features.vocalActivityMask, vocalProbability = features.vocalProbability)
        } finally { withContext(NonCancellable) { cancellation.cancelAndJoin(); engine.close() } }
    }
}
