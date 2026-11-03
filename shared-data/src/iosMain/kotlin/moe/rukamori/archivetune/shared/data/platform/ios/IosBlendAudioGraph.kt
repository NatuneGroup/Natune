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

private const val PCM_RATE = 48_000.0
private const val PCM_FRAMES = 1_024
private const val MAX_PCM_BUFFERS = 8
private const val PREBUFFER_US = 120_000L

/** Band slots each deck's AVAudioUnitEQ allocates, the same bound the output graph's unit uses. */
private const val EQ_UNIT_BANDS = 16uL

/**
 * Two player nodes share one device graph. Only the main-queue pump touches Kotlin or the decoder: AVAudioPlayerNode
 * renders the bounded, already-copied PCM buffers with no Kotlin render/completion callback.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosBlendAudioGraph private constructor(output: CPointer<at_audio_output>, volume: Float) {
    private val engine = AVAudioEngine()
    private val format = AVAudioFormat(
        commonFormat = AVAudioPCMFormatFloat32, sampleRate = PCM_RATE, channels = 2u, interleaved = false,
    )
    var current = Deck(output)
        private set
    var incoming: Deck? = null
        private set
    private val observers = mutableListOf<Any>()
    private var closed = false
    private var suspended = false

    // The tone every deck renders: a blend pulls decoder PCM before the output graph's own units, so the
    // shared pitch and equalizer state must reach these deck units too. A deck gets it when it is attached.
    private var pitchCents = 0f
    private var equalizerEnabled = false
    private var equalizerBands: List<IosEqBand> = emptyList()

    init {
        engine.mainMixerNode.outputVolume = volume
    }

    fun arm(output: CPointer<at_audio_output>): Boolean {
        if (closed || incoming != null) return false
        val deck = Deck(output)
        incoming = deck
        deck.mixer.outputVolume = 0f
        if (at_audio_play(output) == 0) {
            dropIncoming()
            return false
        }
        deck.pump()
        return true
    }

    fun poll(): EnginePlaybackState {
        current.pump()
        incoming?.pump()
        return current.observation()
    }

    fun setVolume(volume: Float) {
        engine.mainMixerNode.outputVolume = volume
    }

    /** The shared pitch in cents on every live deck and the ones armed later. */
    fun setPitch(cents: Float) {
        pitchCents = cents
        applyPitch(current)
        incoming?.let(::applyPitch)
    }

    /** The shared equalizer on every live deck and the ones armed later. */
    fun setEqualizer(enabled: Boolean, bands: List<IosEqBand>) {
        equalizerEnabled = enabled
        equalizerBands = bands
        applyEqualizer(current)
        incoming?.let(::applyEqualizer)
    }

    private fun applyPitch(deck: Deck) {
        deck.pitchUnit.rate = 1f // Tempo stays in the engine; the unit shifts pitch only.
        deck.pitchUnit.pitch = pitchCents
    }

    private fun applyEqualizer(deck: Deck) {
        val units = deck.eqUnit.bands
        for (index in units.indices) {
            val params = units[index]
            val band = equalizerBands.getOrNull(index)
            if (band == null) {
                params.bypass = true
                continue
            }
            params.frequency = band.frequencyHz.coerceIn(20f, 23_900f)
            params.bandwidth = band.bandwidthOctaves.coerceIn(0.05f, 5f)
            params.gain = band.gainDb.coerceIn(-24f, 24f)
            params.filterType = when (band.type) {
                1 -> AVAudioUnitEQFilterTypeLowShelf
                2 -> AVAudioUnitEQFilterTypeHighShelf
                else -> AVAudioUnitEQFilterTypeParametric
            }
            params.bypass = !equalizerEnabled
        }
    }

    fun play(): Boolean {
        if (closed || at_audio_play(current.output) == 0) return false
        suspended = false
        if (!engine.running && !engine.startAndReturnError(null)) {
            failOutput()
            return false
        }
        current.pump()
        current.start()
        return true
    }

    fun pause() {
        current.observation(commit = false)
        incoming?.observation(commit = false)
        suspended = true
        current.node.pause()
        incoming?.node?.pause()
        engine.pause()
        at_audio_pause(current.output)
        incoming?.let { at_audio_pause(it.output) }
    }

    fun seek(positionUs: ULong) {
        if (!current.nativeState().isSeekable) return
        current.clearForSeek()
        at_audio_seek(current.output, positionUs)
    }

    fun promote() {
        val next = incoming ?: return
        current.detach()
        current = next
        current.mixer.outputVolume = 1f
        incoming = null
    }

    fun dropIncoming() {
        incoming?.detach()
        incoming = null
        current.mixer.outputVolume = 1f
    }

    fun outputLatencySeconds(): Double {
        val session = AVAudioSession.sharedInstance().outputLatency
        val presentation = engine.outputNode.presentationLatency
        return listOf(session, presentation).filter { it.isFinite() && it >= 0.0 }.maxOrNull() ?: -1.0
    }

    /** The caller stops the active native output first, so returning it to source-node mode never restarts old audio. */
    fun close() {
        if (closed) return
        closed = true
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
        engine.stop()
        dropIncoming()
        current.detach()
        at_audio_end_manual_output(current.output)
    }

    private fun observeSystem() {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        observers += center.addObserverForName(AVAudioSessionInterruptionNotification, null, queue) { note ->
            if (closed) return@addObserverForName
            val type = (note?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongLongValue
            if (type == AVAudioSessionInterruptionTypeBegan) {
                current.observation(commit = false)
                incoming?.observation(commit = false)
                suspended = true
                current.node.pause()
                incoming?.node?.pause()
                engine.pause()
            } else {
                val options = (note?.userInfo?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.unsignedLongLongValue ?: 0uL
                if (options and AVAudioSessionInterruptionOptionShouldResume != 0uL &&
                    current.nativeState().playWhenReady
                ) {
                    restartPreservingPcm()
                }
            }
        }
        observers += center.addObserverForName(AVAudioSessionRouteChangeNotification, null, queue) { note ->
            if (closed) return@addObserverForName
            val reason = (note?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongLongValue
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) pause()
        }
        observers += center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, null, queue) { _ ->
            if (!closed) failOutput()
        }
        observers += center.addObserverForName(AVAudioEngineConfigurationChangeNotification, engine, queue) { _ ->
            if (!closed && !suspended && current.nativeState().playWhenReady) restartPreservingPcm()
        }
    }

    private fun restartPreservingPcm() {
        engine.stop()
        current.reschedule()
        incoming?.reschedule()
        suspended = false
        if (!play()) failOutput()
    }

    private fun failOutput() {
        suspended = true
        engine.stop()
        at_audio_manual_output_failed(current.output)
    }

    @OptIn(ExperimentalForeignApi::class)
    inner class Deck(val output: CPointer<at_audio_output>) {
        val node = AVAudioPlayerNode()
        val pitchUnit = AVAudioUnitTimePitch()
        val eqUnit = AVAudioUnitEQ(numberOfBands = EQ_UNIT_BANDS)
        val mixer = AVAudioMixerNode()
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
            // The manual pull delivers decoder PCM, so the tone chain is node -> pitch -> equalizer -> mixer.
            engine.attachNode(node)
            engine.attachNode(pitchUnit)
            engine.attachNode(eqUnit)
            engine.attachNode(mixer)
            engine.connect(node, to = pitchUnit, format = format)
            engine.connect(pitchUnit, to = eqUnit, format = format)
            engine.connect(eqUnit, to = mixer, format = format)
            engine.connect(mixer, to = engine.mainMixerNode, format = format)
            applyPitch(this)
            applyEqualizer(this)
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
                val buffer = if (free.isEmpty()) AVAudioPCMBuffer(format, PCM_FRAMES.toUInt()) else free.removeFirst()
                val channels = buffer.floatChannelData ?: run {
                    failOutput()
                    return
                }
                for (frame in 0 until count) {
                    channels[0]!![frame] = scratch[frame * 2]
                    channels[1]!![frame] = scratch[frame * 2 + 1]
                }
                buffer.frameLength = count.toUInt()
                // After starvation the node's sample time can run past the last scheduled frame. Schedule new real PCM
                // ahead of that observed frame, never back into an elapsed player timeline.
                val start = maxOf(nextFrame, if (started && queued.isEmpty()) renderedFrame() + PCM_FRAMES else 0L)
                val span = IosPcmSpan(start, start + count, beforeUs, raw.positionUs)
                val speed = ((span.endUs - span.startUs) * PCM_RATE / (count * 1_000_000.0)).toFloat()
                    .takeIf { it.isFinite() && it > 0f } ?: raw.playbackSpeed
                queued.addLast(Chunk(buffer, span, speed))
                node.scheduleBuffer(buffer, atTime = AVAudioTime(sampleTime = start, atRate = PCM_RATE), options = 0uL, completionHandler = null)
                nextFrame = span.endFrame
            }
            if (this === current && !started && queued.isNotEmpty()) start()
        }

        fun start(atTime: AVAudioTime? = null) {
            if (closed || suspended || queued.isEmpty()) return
            if (atTime == null) node.play() else node.playAtTime(atTime)
            started = true
        }

        val hasPcm: Boolean get() = queued.isNotEmpty()

        /** The decoder has found EOF ahead of the device. Its final queued frame gives a gapless native start time. */
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
            node.stop()
            queued.forEach { free.addLast(it.buffer) }
            queued.clear()
            nextFrame = 0L
            playedFrame = 0L
            started = false
            refilling = true
        }

        /** Configuration changes unschedule player buffers. Requeue only their unplayed PCM without reopening a lease. */
        fun reschedule() {
            val old = queued.toList()
            val lastFrame = playedFrame
            node.stop()
            queued.clear()
            free.clear()
            nextFrame = 0L
            playedFrame = 0L
            started = false
            old.forEach { chunk ->
                val skip = (lastFrame - chunk.span.startFrame).coerceIn(0L, chunk.span.endFrame - chunk.span.startFrame).toInt()
                val count = chunk.buffer.frameLength.toInt() - skip
                if (count <= 0) return@forEach
                val replacement = AVAudioPCMBuffer(format, PCM_FRAMES.toUInt())
                val source = chunk.buffer.floatChannelData ?: run { failOutput(); return }
                val target = replacement.floatChannelData ?: run { failOutput(); return }
                for (channel in 0..1) for (frame in 0 until count) target[channel]!![frame] = source[channel]!![frame + skip]
                replacement.frameLength = count.toUInt()
                val span = IosPcmSpan(nextFrame, nextFrame + count, chunk.span.positionUs(chunk.span.startFrame + skip), chunk.span.endUs)
                queued.addLast(Chunk(replacement, span, chunk.speed))
                node.scheduleBuffer(replacement, atTime = AVAudioTime(sampleTime = nextFrame, atRate = PCM_RATE), options = 0uL, completionHandler = null)
                nextFrame = span.endFrame
            }
        }

        fun detach() {
            node.stop()
            engine.detachNode(node)
            engine.detachNode(pitchUnit)
            engine.detachNode(eqUnit)
            engine.detachNode(mixer)
            queued.clear()
            free.clear()
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
            try {
                graph = IosBlendAudioGraph(output, volume)
                graph.engine.prepare()
                if (!graph.engine.startAndReturnError(null)) {
                    graph.close()
                    return null
                }
                graph.observeSystem()
                graph.current.pump()
                return graph
            } catch (_: Exception) {
                graph?.close()
                if (graph == null) at_audio_end_manual_output(output)
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
