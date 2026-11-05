package moe.rukamori.archivetune.shared.data.platform.ios

import cnames.structs.at_audio_output
import kotlinx.cinterop.*
import moe.rukamori.archivetune.nativeaudio.*
import moe.rukamori.archivetune.shared.data.playback.EnginePlaybackState
import moe.rukamori.archivetune.shared.data.playback.IosPcmSpan
import platform.AVFAudio.*
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val PCM_RATE = 48_000.0
private const val PCM_FRAMES = 1_024
private const val MAX_PCM_BUFFERS = 8
private const val PREBUFFER_US = 120_000L
private const val EQ_UNIT_BANDS = 16u

/** Two decks share one device. Apple graph mutations are native exception-safe calls, never Kotlin try/catch. */
@OptIn(ExperimentalForeignApi::class)
internal class IosBlendAudioGraph private constructor(
    output: CPointer<at_audio_output>,
    private var volume: Float,
    private var engine: AVAudioEngine,
    private var mainMixer: AVAudioMixerNode,
    private val format: AVAudioFormat,
    private var canonical: AVAudioFormat,
) {
    private var pitchCents = 0f
    private var equalizerEnabled = false
    private var equalizerBands: List<IosEqBand> = emptyList()
    private var preampDb = 0f
    private val observers = mutableListOf<Any>()
    private var configurationObserver: Any? = null
    private var closed = false
    private var suspended = false
    private var graphDirty = false
    private var rebuildQueued = false
    private var plainGraph = false
    private var resumeEpoch = 0L

    var current = Deck(output)
        private set
    var incoming: Deck? = null
        private set

    init {
        check(at_graph_volume(mainMixer, volume) != 0)
    }

    fun arm(output: CPointer<at_audio_output>): Boolean {
        if (closed || incoming != null) return false
        val deck = try { Deck(output, 0f) } catch (_: Exception) { return false }
        incoming = deck
        if (at_audio_play(output) == 0) {
            dropIncoming()
            return false
        }
        deck.pump()
        if (suspended) { dropIncoming(); return false }
        return true
    }

    fun poll(): EnginePlaybackState {
        current.pump()
        incoming?.pump()
        return current.observation()
    }

    fun setVolume(volume: Float) {
        this.volume = volume
        if (at_graph_volume(mainMixer, volume) == 0) failOutput()
    }

    fun setPitch(cents: Float) {
        pitchCents = cents
        if (!current.applyTone() || incoming?.applyTone() == false) { plainGraph = true; invalidateGraph() }
    }

    fun setTone(enabled: Boolean, bands: List<IosEqBand>, preampDb: Float) {
        equalizerEnabled = enabled
        equalizerBands = bands
        this.preampDb = preampDb
        if (!current.applyTone() || incoming?.applyTone() == false) { plainGraph = true; invalidateGraph() }
    }

    fun play(): Boolean {
        if (closed || at_audio_play(current.output) == 0) return false
        if (graphDirty && !rebuildWithFallback()) { failOutput(); return false }
        if (!startEngine()) { failOutput(); return false }
        suspended = false
        incoming?.let { if (at_audio_play(it.output) == 0) { failOutput(); return false } }
        current.pump()
        current.start()
        incoming?.pump() // The handoff owner reschedules fade/gapless starts, never start a future deck eagerly.
        return !suspended
    }

    private fun startEngine(): Boolean {
        if (at_graph_prepare(engine) != 0 && at_graph_start(engine) != 0) return true
        if (plainGraph) return false
        plainGraph = true
        graphDirty = true
        return rebuildPreservingPcm() && at_graph_start(engine) != 0
    }

    fun pause() {
        resumeEpoch++ // A queued automatic rebuild must never override this listener pause.
        current.observation(commit = false)
        incoming?.observation(commit = false)
        suspended = true
        current.pauseNode()
        incoming?.pauseNode()
        if (at_graph_pause(engine) == 0) failOutput()
        at_audio_pause(current.output)
        incoming?.let { at_audio_pause(it.output) }
    }

    fun seek(positionUs: ULong) {
        if (!current.nativeState().isSeekable) return
        current.clearForSeek()
        at_audio_seek(current.output, positionUs)
    }

    fun promote(): Boolean {
        val next = incoming ?: return false
        next.setGain(1f)
        if (suspended || !current.detach()) { failOutput(); return false }
        current = next
        incoming = null
        return true
    }

    fun dropIncoming() {
        val detached = incoming?.detach() ?: true
        incoming = null
        if (!detached) failOutput()
        current.setGain(1f)
    }

    fun outputLatencySeconds(): Double = at_graph_latency(engine)

    /** The caller stops the native output first; handing it back never restarts the outgoing occurrence. */
    fun close() {
        if (closed) return
        closed = true
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        configurationObserver?.let { center.removeObserver(it) }
        configurationObserver = null
        val stopped = at_graph_stop(engine) != 0
        val incomingDetached = incoming?.detach() ?: true
        incoming = null
        val currentDetached = current.detach()
        if (!stopped || !incomingDetached || !currentDetached) at_audio_manual_output_failed(current.output)
        at_audio_end_manual_output(current.output)
    }

    private fun observeSystem() {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        observers += center.addObserverForName(AVAudioSessionInterruptionNotification, null, queue) { note ->
            if (closed) return@addObserverForName
            val type = (note?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongLongValue
            if (type == AVAudioSessionInterruptionTypeBegan) {
                resumeEpoch++
                current.observation(commit = false)
                incoming?.observation(commit = false)
                suspended = true
                current.pauseNode()
                incoming?.pauseNode()
                if (at_graph_pause(engine) == 0) failOutput()
            } else {
                graphDirty = true
                val options = (note?.userInfo?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.unsignedLongLongValue ?: 0uL
                if (options and AVAudioSessionInterruptionOptionShouldResume != 0uL && current.nativeState().playWhenReady) {
                    val epoch = resumeEpoch
                    dispatch_async(dispatch_get_main_queue()) {
                        if (!closed && epoch == resumeEpoch && current.nativeState().playWhenReady && !play()) failOutput()
                    }
                }
            }
        }
        observers += center.addObserverForName(AVAudioSessionRouteChangeNotification, null, queue) { note ->
            if (closed) return@addObserverForName
            val reason = (note?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongLongValue
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) pause()
            invalidateGraph()
        }
        observers += center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, null, queue) { _ ->
            if (!closed) failOutput()
        }
        observeConfiguration()
    }

    private fun observeConfiguration() {
        val center = NSNotificationCenter.defaultCenter
        configurationObserver?.let { center.removeObserver(it) }
        val observedEngine = engine
        configurationObserver = center.addObserverForName(
            AVAudioEngineConfigurationChangeNotification, observedEngine, NSOperationQueue.mainQueue,
        ) { _ ->
            dispatch_async(dispatch_get_main_queue()) {
                if (!closed && engine === observedEngine) invalidateGraph()
            }
        }
    }

    private fun invalidateGraph() {
        graphDirty = true
        if (closed || rebuildQueued || suspended || !current.nativeState().playWhenReady) return
        current.observation(commit = false)
        incoming?.observation(commit = false)
        suspended = true // Freeze the PCM spans before another pump can consume the decoder.
        rebuildQueued = true
        val epoch = resumeEpoch
        dispatch_async(dispatch_get_main_queue()) {
            rebuildQueued = false
            if (!closed && epoch == resumeEpoch && current.nativeState().playWhenReady && !play()) failOutput()
        }
    }

    private fun rebuildWithFallback(): Boolean {
        if (rebuildPreservingPcm()) return true
        if (plainGraph) return false
        plainGraph = true
        return rebuildPreservingPcm()
    }

    /** Replace the graph, not just start an engine whose Audio Unit formats belong to the previous route.
     * Deck PCM remains 48 kHz stereo; per-deck mixers convert it to the freshly validated hardware-rate tone chain. */
    private fun rebuildPreservingPcm(): Boolean {
        suspended = true
        configurationObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        configurationObserver = null
        val stopped = at_graph_stop(engine) != 0
        val currentDetached = current.detachNodes()
        val incomingDetached = incoming?.detachNodes() ?: true
        if (!stopped || !currentDetached || !incomingDetached) return false
        return try {
            engine = checkNotNull(at_graph_create())
            canonical = checkNotNull(at_graph_output_format(engine))
            mainMixer = checkNotNull(at_graph_main_mixer(engine))
            current.attach()
            incoming?.attach()
            if (!current.reschedule() || incoming?.reschedule() == false ||
                at_graph_volume(mainMixer, volume) == 0 || at_graph_prepare(engine) == 0
            ) return false
            graphDirty = false
            observeConfiguration()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun failOutput() {
        if (closed) return
        resumeEpoch++
        suspended = true
        at_graph_stop(engine)
        at_audio_manual_output_failed(current.output)
    }

    inner class Deck(val output: CPointer<at_audio_output>, private var gain: Float = 1f) {
        val node = checkNotNull(at_graph_player_create())
        private val converter = checkNotNull(at_graph_mixer_create())
        private val pitchUnit = at_graph_pitch_create()
        private val eqUnit = at_graph_eq_create(EQ_UNIT_BANDS)
        private val mixer = checkNotNull(at_graph_mixer_create())
        private val attached = mutableListOf<AVAudioNode>()
        private var toneActive = false
        private val scratch = FloatArray(PCM_FRAMES * 2)
        private val queued = ArrayDeque<Chunk>()
        private val free = ArrayDeque<AVAudioPCMBuffer>()
        private var nextFrame = 0L
        private var playedFrame = 0L
        private var mediaUs = nativeState().positionUs.coerceAtLeast(0L)
        private var previousMediaUs = mediaUs
        private var mediaSpeed = nativeState().playbackSpeed
        private var waitingForSeek: Long? = null
        private var refilling = true
        var started = false
            private set

        init {
            try { attach() } catch (failure: Exception) { detachNodes(); throw failure }
        }

        fun attach() {
            fun attachNode(unit: AVAudioNode) {
                check(at_graph_attach_node(engine, unit) != 0)
                attached += unit
            }
            attachNode(node)
            attachNode(converter)
            attachNode(mixer)
            check(at_graph_connect(engine, node, converter, format) != 0)
            toneActive = !plainGraph && pitchUnit != null && eqUnit != null
            if (toneActive) {
                val pitch = checkNotNull(pitchUnit)
                val eq = checkNotNull(eqUnit)
                try {
                    attachNode(pitch)
                    attachNode(eq)
                    check(at_graph_connect(engine, converter, pitch, canonical) != 0)
                    check(at_graph_connect(engine, pitch, eq, canonical) != 0)
                    check(at_graph_connect(engine, eq, mixer, canonical) != 0)
                    check(applyTone())
                } catch (_: Exception) {
                    // Optional Audio Units cannot make a successor crash or silence its outgoing deck.
                    if (eq in attached) { check(at_graph_detach_node(engine, eq) != 0); attached.remove(eq) }
                    if (pitch in attached) { check(at_graph_detach_node(engine, pitch) != 0); attached.remove(pitch) }
                    toneActive = false
                }
            }
            if (!toneActive) check(at_graph_connect(engine, converter, mixer, canonical) != 0)
            check(at_graph_connect(engine, mixer, mainMixer, canonical) != 0)
            check(at_graph_volume(mixer, gain) != 0)
        }

        fun applyTone(): Boolean {
            if (!toneActive) return true
            val pitch = pitchUnit ?: return false
            val eq = eqUnit ?: return false
            if (at_graph_pitch(pitch, pitchCents) == 0) return false
            if (at_graph_eq_gain(eq, preampDb) == 0) return false
            for (index in 0 until EQ_UNIT_BANDS.toInt()) {
                val band = equalizerBands.getOrNull(index)
                if (at_graph_eq_band(
                        eq, index.toUInt(), (band?.type ?: 0).toUInt(), band?.frequencyHz ?: 1_000f,
                        band?.bandwidthOctaves ?: 1f, band?.gainDb ?: 0f,
                        if (equalizerEnabled && band != null) 1 else 0, canonical.sampleRate,
                    ) == 0
                ) return false
            }
            return true
        }

        fun setGain(gain: Float) {
            this.gain = gain
            if (at_graph_volume(mixer, gain) == 0) failOutput()
        }

        fun pauseNode() {
            if (at_graph_player_pause(node) == 0) failOutput()
        }

        fun nativeState(): EnginePlaybackState = iosNativePlaybackState(output)

        fun observation(commit: Boolean = true): EnginePlaybackState {
            val raw = nativeState()
            val frame = renderedFrame()
            while (queued.isNotEmpty() && frame >= queued.first().span.endFrame) {
                val done = queued.removeFirst()
                mediaUs = done.span.endUs
                mediaSpeed = done.speed
                free.addLast(done.buffer)
            }
            queued.firstOrNull()?.let { chunk ->
                if (frame >= chunk.span.startFrame) {
                    mediaUs = chunk.span.positionUs(frame)
                    mediaSpeed = chunk.speed
                }
            }
            val moving = !suspended && engine.running && node.playing && waitingForSeek == null &&
                raw.errorCode == EnginePlaybackState.ERROR_NONE && mediaUs > previousMediaUs
            if (commit) previousMediaUs = mediaUs
            val ended = raw.isEnded && queued.isEmpty()
            var flags = raw.flags and (EnginePlaybackState.FLAG_ADVANCING or EnginePlaybackState.FLAG_BUFFERING or
                EnginePlaybackState.FLAG_ENDED).inv()
            if (moving) flags = flags or EnginePlaybackState.FLAG_ADVANCING
            if (ended) flags = flags or EnginePlaybackState.FLAG_ENDED
            if (raw.playWhenReady && !ended && !moving && (queued.isEmpty() || waitingForSeek != null)) {
                flags = flags or EnginePlaybackState.FLAG_BUFFERING
            }
            return raw.copy(flags = flags, positionUs = mediaUs, playbackSpeed = mediaSpeed)
        }

        fun pump() {
            observation(commit = false)
            var raw = nativeState()
            waitingForSeek?.let { generation ->
                if (raw.seekGeneration == generation && raw.errorCode == EnginePlaybackState.ERROR_NONE) return
                waitingForSeek = null
                mediaUs = raw.positionUs.coerceAtLeast(0L)
                previousMediaUs = mediaUs
                mediaSpeed = raw.playbackSpeed
            }
            if (closed || suspended || !raw.playWhenReady || raw.errorCode != EnginePlaybackState.ERROR_NONE) return
            if (refilling && raw.isBuffering && raw.bufferedUs - raw.positionUs < PREBUFFER_US && !raw.isEnded &&
                (raw.durationUs < 0L || raw.bufferedUs < raw.durationUs)
            ) return
            refilling = false
            while (queued.size < MAX_PCM_BUFFERS) {
                val beforeUs = raw.positionUs
                val count = scratch.usePinned { at_audio_pull_manual_output(output, it.addressOf(0), PCM_FRAMES.toUInt()) }.toInt()
                if (count <= 0) {
                    refilling = queued.isEmpty()
                    break
                }
                raw = nativeState()
                val buffer = (if (free.isEmpty()) at_graph_buffer_create(format, PCM_FRAMES.toUInt()) else free.removeFirst())
                    ?: run { failOutput(); return }
                val channels = buffer.floatChannelData ?: run { failOutput(); return }
                for (frame in 0 until count) {
                    channels[0]!![frame] = scratch[frame * 2]
                    channels[1]!![frame] = scratch[frame * 2 + 1]
                }
                buffer.frameLength = count.toUInt()
                val start = maxOf(nextFrame, if (started && queued.isEmpty()) renderedFrame() + PCM_FRAMES else 0L)
                val span = IosPcmSpan(start, start + count, beforeUs, raw.positionUs)
                val speed = ((span.endUs - span.startUs) * PCM_RATE / (count * 1_000_000.0)).toFloat()
                    .takeIf { it.isFinite() && it > 0f } ?: raw.playbackSpeed
                if (at_graph_schedule(node, buffer, AVAudioTime(sampleTime = start, atRate = PCM_RATE)) == 0) {
                    failOutput()
                    return
                }
                queued.addLast(Chunk(buffer, span, speed))
                nextFrame = span.endFrame
            }
            if (this === current && !started && queued.isNotEmpty()) start()
        }

        fun start(atTime: AVAudioTime? = null) {
            if (closed || suspended || queued.isEmpty()) return
            if (at_graph_player_play(node, atTime) == 0) { failOutput(); return }
            started = true
        }

        val hasPcm: Boolean get() = queued.isNotEmpty()

        fun endTime(): AVAudioTime? {
            if (!nativeState().isEnded || queued.isEmpty()) return null
            val render = node.lastRenderTime ?: return null
            val player = node.playerTimeForNodeTime(render) ?: return null
            if (!render.hostTimeValid || !player.sampleTimeValid) return null
            val framesLeft = (queued.last().span.endFrame - player.sampleTime).coerceAtLeast(0L)
            return AVAudioTime(hostTime = render.hostTime + AVAudioTime.hostTimeForSeconds(framesLeft / PCM_RATE))
        }

        fun clearForSeek() {
            waitingForSeek = nativeState().seekGeneration
            if (at_graph_player_stop(node) == 0) failOutput()
            queued.forEach { free.addLast(it.buffer) }
            queued.clear()
            nextFrame = 0L
            playedFrame = 0L
            started = false
            refilling = true
        }

        /** Reschedule only unplayed PCM at its original rate; the new converter owns the route-rate conversion. */
        fun reschedule(): Boolean {
            val lastFrame = playedFrame
            if (at_graph_player_stop(node) == 0) return false
            val replacements = ArrayDeque<Chunk>()
            var replacementFrame = 0L
            queued.forEach { chunk ->
                val skip = (lastFrame - chunk.span.startFrame).coerceIn(0L, chunk.span.endFrame - chunk.span.startFrame).toInt()
                val count = chunk.buffer.frameLength.toInt() - skip
                if (count <= 0) return@forEach
                val replacement = at_graph_buffer_create(format, PCM_FRAMES.toUInt()) ?: return false
                val source = chunk.buffer.floatChannelData ?: return false
                val target = replacement.floatChannelData ?: return false
                for (channel in 0..1) for (frame in 0 until count) target[channel]!![frame] = source[channel]!![frame + skip]
                replacement.frameLength = count.toUInt()
                val span = IosPcmSpan(replacementFrame, replacementFrame + count, chunk.span.positionUs(chunk.span.startFrame + skip), chunk.span.endUs)
                if (at_graph_schedule(node, replacement, AVAudioTime(sampleTime = replacementFrame, atRate = PCM_RATE)) == 0) return false
                replacements.addLast(Chunk(replacement, span, chunk.speed))
                replacementFrame = span.endFrame
            }
            // A rejected schedule must leave all decoded PCM available for the plain-graph retry.
            queued.clear()
            queued.addAll(replacements)
            free.clear()
            nextFrame = replacementFrame
            playedFrame = 0L
            started = false
            return true
        }

        fun detachNodes(): Boolean {
            var detached = at_graph_player_stop(node) != 0
            attached.asReversed().toList().forEach { unit ->
                if (at_graph_detach_node(engine, unit) != 0) attached.remove(unit) else detached = false
            }
            return detached
        }

        fun detach(): Boolean {
            val detached = detachNodes()
            queued.clear()
            free.clear()
            return detached
        }

        private fun renderedFrame(): Long {
            if (!started || suspended || !engine.running) return playedFrame
            val render = node.lastRenderTime ?: return playedFrame
            val player = node.playerTimeForNodeTime(render) ?: return playedFrame
            if (!player.sampleTimeValid) return playedFrame
            playedFrame = maxOf(playedFrame, player.sampleTime)
            return playedFrame
        }
    }

    private class Chunk(val buffer: AVAudioPCMBuffer, val span: IosPcmSpan, val speed: Float)

    companion object {
        fun open(output: CPointer<at_audio_output>, volume: Float): IosBlendAudioGraph? {
            if (at_audio_begin_manual_output(output) == 0) return null
            var graph: IosBlendAudioGraph? = null
            val engine = at_graph_create()
            try {
                val device = checkNotNull(engine)
                graph = IosBlendAudioGraph(
                    output, volume, device, checkNotNull(at_graph_main_mixer(device)),
                    checkNotNull(at_graph_pcm_format(PCM_RATE, 2u)), checkNotNull(at_graph_output_format(device)),
                )
                if (!graph.startEngine()) { graph.close(); return null }
                graph.suspended = false
                graph.observeSystem()
                graph.current.pump()
                return graph
            } catch (_: Exception) {
                graph?.close()
                if (graph == null) {
                    engine?.let { at_graph_stop(it) }
                    at_audio_end_manual_output(output)
                }
                return null
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosNativePlaybackState(output: CPointer<at_audio_output>): EnginePlaybackState = memScoped {
    val snapshot = alloc<ae_snapshot>()
    at_audio_snapshot(output, snapshot.ptr)
    EnginePlaybackState(
        snapshot.flags.toInt(), snapshot.error_code.toInt(), snapshot.load_generation.toLong(),
        snapshot.seek_generation.toLong(), snapshot.error_sequence.toLong(), snapshot.position_us,
        snapshot.duration_us, snapshot.buffered_us, snapshot.playback_speed,
    )
}
