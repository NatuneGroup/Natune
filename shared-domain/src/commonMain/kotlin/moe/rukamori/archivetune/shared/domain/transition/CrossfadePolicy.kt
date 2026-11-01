package moe.rukamori.archivetune.shared.domain.transition

/**
 * Pure decision rules for crossfades and gapless handoffs, ported from the shipped Android
 * `CrossfadePolicy`. The platform adapter drives the player; every rule it must obey lives here.
 */
object CrossfadePolicy {
    fun outgoingElapsedMs(
        startPositionMs: Long,
        currentPositionMs: Long,
        durationMs: Long,
    ): Long {
        if (startPositionMs < 0L || currentPositionMs <= startPositionMs || durationMs <= 0L) return 0L
        return minOf(currentPositionMs - startPositionMs, durationMs)
    }

    fun advanceFadeElapsedMs(
        elapsedMs: Long,
        previousPositionMs: Long,
        currentPositionMs: Long,
        durationMs: Long,
        incomingAdvancing: Boolean,
    ): Long {
        if (durationMs <= 0L) return 0L
        val boundedElapsedMs = elapsedMs.coerceIn(0L, durationMs)
        if (!incomingAdvancing) return boundedElapsedMs
        val elapsedDeltaMs =
            outgoingElapsedMs(
                startPositionMs = previousPositionMs,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs - boundedElapsedMs,
            )
        return boundedElapsedMs + elapsedDeltaMs
    }

    fun shouldAbortForIncomingStall(
        playbackRequested: Boolean,
        incomingAdvancing: Boolean,
        stallElapsedMs: Long,
        maximumStallMs: Long,
    ): Boolean =
        playbackRequested &&
            !incomingAdvancing &&
            maximumStallMs >= 0L &&
            stallElapsedMs >= maximumStallMs

    /**
     * Clamps the requested crossfade into a usable range for the current track. Returns null when the
     * track is too short, or of unknown duration, to crossfade.
     */
    fun effectiveDurationMs(
        requestedMs: Long,
        trackDurationMs: Long,
        endGuardMs: Long,
        minDurationMs: Long,
        unsetTime: Long,
    ): Long? {
        if (trackDurationMs == unsetTime || trackDurationMs <= 0L) return null
        val maxDuration = trackDurationMs - endGuardMs
        if (maxDuration < minDurationMs) return null
        return requestedMs.coerceIn(minDurationMs, maxDuration)
    }

    /**
     * Repeat-one crossfades back into the current item, anything else targets the next item. Returns null
     * when there is no valid target.
     */
    fun resolveTargetIndex(
        repeatOne: Boolean,
        currentIndex: Int,
        nextIndex: Int,
        itemCount: Int,
        unsetIndex: Int,
    ): Int? {
        if (itemCount <= 0 || currentIndex !in 0 until itemCount) return null
        val target = if (repeatOne) currentIndex else nextIndex
        if (target == unsetIndex || target !in 0 until itemCount) return null
        if (!repeatOne && target == currentIndex) return null
        return target
    }

    /**
     * Two consecutive queue items belong to one album sequence when they carry the same album
     * identity. The host supplies the best key it has (provider album id first, album title when
     * no id exists); a missing key on either side can never prove a sequence.
     */
    fun isSequentialAlbumTransition(currentAlbumKey: String?, nextAlbumKey: String?): Boolean =
        currentAlbumKey != null && currentAlbumKey == nextAlbumKey

    /**
     * Whether a blend — a crossfade or an automix — may arm at all. Repeat-one replays the same
     * item, so there is nothing to blend into, and a same-album sequence keeps its mastered gap
     * (a gapless splice, never a mix), so both suppress the blend while the queue's ordinary
     * transition still applies. [resolveTargetIndex] itself is untouched: it only describes where
     * a blend would land, and hosts may consult it for other purposes.
     */
    fun suppressesBlend(
        repeatOne: Boolean,
        gaplessAlbums: Boolean,
        currentAlbumKey: String?,
        nextAlbumKey: String?,
    ): Boolean =
        repeatOne || (gaplessAlbums && isSequentialAlbumTransition(currentAlbumKey, nextAlbumKey))

    /** Snapshot of the incoming player used to decide whether a fade may start. */
    data class ReadinessSnapshot(
        val isReady: Boolean,
        val isIdle: Boolean,
        val isEnded: Boolean,
        val hasError: Boolean,
        val bufferedEnough: Boolean,
    )

    /** True only when the incoming player is ready and has buffered enough audio. */
    fun isReadyForFadeStart(snapshot: ReadinessSnapshot): Boolean =
        !snapshot.hasError && !snapshot.isEnded && snapshot.isReady && snapshot.bufferedEnough

    /** True when the readiness wait must abort because the incoming player reached a terminal state. */
    fun mustAbortReadiness(snapshot: ReadinessSnapshot): Boolean = snapshot.hasError || snapshot.isEnded

    /** Snapshot used to decide whether the incoming track's play position really advanced. */
    data class AudioAdvancementSnapshot(
        val isReady: Boolean,
        val isPlaying: Boolean,
        val hasError: Boolean,
        val positionMs: Long,
        val previousPositionMs: Long,
    )

    /** True only when the play position advanced between polls. */
    fun hasAudioAdvanced(snapshot: AudioAdvancementSnapshot): Boolean =
        !snapshot.hasError &&
            snapshot.isReady &&
            snapshot.isPlaying &&
            snapshot.positionMs > snapshot.previousPositionMs

    /** Snapshot used when the fade completes and the incoming deck may be promoted. */
    data class PromotionSnapshot(
        val generationMatches: Boolean,
        val targetIndex: Int,
        val incomingItemMatchesTarget: Boolean,
        val hasError: Boolean,
        val isIdle: Boolean,
        val isEnded: Boolean,
        val isReady: Boolean,
        val isPlaying: Boolean,
        val playbackRequested: Boolean,
        val unsetIndex: Int,
        /**
         * The outgoing track ran out underneath the blend, so nothing is left to mix out of. The incoming
         * deck is promoted even while it only buffers, the same way a mid-song rebuffer resumes.
         */
        val outgoingRanOut: Boolean = false,
    )

    /**
     * The incoming deck may be promoted only while the fade generation is current (no cancel or skip
     * happened), its target still exists, and it is not terminal. Otherwise the outgoing deck stays
     * authoritative and must not be released.
     */
    fun mayPromote(snapshot: PromotionSnapshot): Boolean =
        snapshot.generationMatches &&
            snapshot.targetIndex != snapshot.unsetIndex &&
            snapshot.incomingItemMatchesTarget &&
            !snapshot.hasError &&
            !snapshot.isIdle &&
            !snapshot.isEnded &&
            (!snapshot.playbackRequested || snapshot.outgoingRanOut || (snapshot.isReady && snapshot.isPlaying))

    fun shouldResumeAfterEnded(
        playbackRequested: Boolean,
        hasNextItem: Boolean,
        repeatOne: Boolean,
        suppressAutoPlayback: Boolean,
    ): Boolean =
        playbackRequested &&
            !suppressAutoPlayback &&
            (hasNextItem || repeatOne)
}
