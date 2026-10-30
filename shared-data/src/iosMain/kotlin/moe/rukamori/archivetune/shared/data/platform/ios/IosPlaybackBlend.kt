package moe.rukamori.archivetune.shared.data.platform.ios

import cnames.structs.at_audio_output
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import moe.rukamori.archivetune.nativeaudio.at_audio_speed
import moe.rukamori.archivetune.shared.data.playback.EnginePlaybackState
import moe.rukamori.archivetune.shared.data.playback.IosBlendProgress
import moe.rukamori.archivetune.shared.domain.transition.CrossfadePolicy

/** Main-queue handoff ownership. A refused/stalled successor never stops or replaces the outgoing occurrence. */
@OptIn(ExperimentalForeignApi::class)
internal class IosPlaybackBlend private constructor(private val graph: IosBlendAudioGraph) {
    private var next: IosBlendSource? = null
    private var overlapMs = 0L
    private var fadeStartMs = 0L
    private var progress: IosBlendProgress? = null
    private var stalledAtNanos: Long? = null
    val hasNext: Boolean get() = next != null

    class Read(val state: EnginePlaybackState, val promoted: IosBlendSource? = null)

    /** Success transfers [source] here; refusal leaves it with the caller. */
    fun arm(source: IosBlendSource, overlapMs: Long): Boolean {
        if (hasNext || overlapMs < 0L) return false
        val outgoing = graph.current.observation()
        if (!outgoing.playWhenReady || outgoing.isEnded || outgoing.durationUs <= 0L ||
            outgoing.errorCode != EnginePlaybackState.ERROR_NONE
        ) return false
        if (!graph.arm(source.output)) return false
        next = source
        this.overlapMs = overlapMs
        fadeStartMs = (outgoing.durationUs / 1_000L - overlapMs).coerceAtLeast(0L)
        progress = null
        stalledAtNanos = null
        return true
    }

    fun poll(nowNanos: Long): Read {
        val outgoing = graph.poll()
        val source = next ?: return Read(outgoing)
        val incoming = graph.incoming ?: return Read(outgoing)
        val incomingState = incoming.observation()
        if (!outgoing.playWhenReady || outgoing.errorCode != EnginePlaybackState.ERROR_NONE ||
            incomingState.errorCode != EnginePlaybackState.ERROR_NONE || incomingState.isEnded
        ) {
            cancelNext()
            return Read(outgoing)
        }
        if (!incoming.started && incoming.hasPcm) {
            if (overlapMs == 0L) {
                val endTime = graph.current.endTime()
                if (endTime != null || outgoing.isEnded) {
                    incoming.mixer.outputVolume = 1f
                    incoming.start(endTime)
                }
            } else if (outgoing.positionUs / 1_000L >= fadeStartMs) {
                // A slow preparation shortens the fade to the actual outgoing media still available.
                if (progress == null) {
                    val remainingMs = ((outgoing.durationUs - outgoing.positionUs) / 1_000L).coerceAtLeast(1L)
                    progress = IosBlendProgress(minOf(overlapMs, remainingMs), outgoing.positionUs / 1_000L)
                }
                incoming.start()
            }
        }
        if (!incoming.started) return Read(outgoing)
        if (overlapMs > 0L) {
            val gains = progress?.advance(
                outgoing.positionUs / 1_000L, incomingState.positionUs / 1_000L, incomingState.isAdvancing,
            )
            if (gains != null) {
                graph.current.mixer.outputVolume = gains.outgoing
                incoming.mixer.outputVolume = gains.incoming
            }
            if (incomingState.isAdvancing) {
                stalledAtNanos = null
            } else {
                val stalledSince = stalledAtNanos ?: nowNanos.also { stalledAtNanos = it }
                if (CrossfadePolicy.shouldAbortForIncomingStall(
                        playbackRequested = outgoing.playWhenReady,
                        incomingAdvancing = false,
                        stallElapsedMs = (nowNanos - stalledSince).coerceAtLeast(0L) / 1_000_000L,
                        maximumStallMs = 250L,
                    )
                ) {
                    cancelNext()
                    return Read(outgoing)
                }
            }
        }
        val finished = outgoing.isEnded || (overlapMs > 0L && progress?.complete == true)
        if (!finished || !incomingState.isAdvancing) return Read(outgoing)
        graph.promote()
        next = null
        progress = null
        stalledAtNanos = null
        return Read(incomingState, source)
    }

    fun cancelNext(occurrenceId: String? = null) {
        val source = next ?: return
        if (occurrenceId != null && source.request.occurrenceId != occurrenceId) return
        graph.dropIncoming()
        next = null
        progress = null
        stalledAtNanos = null
        source.release()
    }

    fun setVolume(volume: Float) = graph.setVolume(volume)

    fun setSpeed(speed: Float) {
        at_audio_speed(graph.current.output, speed)
        next?.let { at_audio_speed(it.output, speed) }
    }

    fun play(): Boolean = graph.play()
    fun pause() = graph.pause()
    fun seek(positionUs: ULong) = graph.seek(positionUs)
    fun outputLatencySeconds(): Double = graph.outputLatencySeconds()

    fun close() {
        cancelNext()
        graph.close()
    }

    companion object {
        fun open(output: CPointer<at_audio_output>, volume: Float): IosPlaybackBlend? =
            IosBlendAudioGraph.open(output, volume)?.let(::IosPlaybackBlend)
    }
}
