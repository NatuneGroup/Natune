package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureClassifier
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRecoveryAction
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRecoveryBudget
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRecoveryPolicy
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.playback.playbackCauses
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.QueueSourceRecovery
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication
import moe.rukamori.archivetune.shared.domain.songmetadata.canonicalSong
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.StreamRecoveryTracker

/**
 * Queue-lock confined. A recovered load keeps the same occurrence, user intent and audible position.
 *
 * The failure taxonomy and per-song budget decide what a failure is worth spending: engine-side
 * actions keep the resolved locator, while the resolve-side actions spend one of the budget's
 * re-resolves through the same source-fallback chain as before — one fresh URL per source first,
 * then the ordinary next source — so provider order and the audio-authenticity rule (a rejected
 * attempt never reuses its cached URL) are unchanged.
 */
internal class QueueStreamRecovery(
    private val player: MusicPlayerController,
    private val locators: QueueLocatorResolver,
    private val nowMs: () -> Long,
) {
    private val attempts = StreamRecoveryTracker<AudioSourceType>()
    /** Recovery budget per media id, as the host's per-video budget map; cleared with the load lineage. */
    private val budgets = HashMap<String, PlaybackRecoveryBudget>()
    private var active: PlaybackRequest? = null
    private var loadedPositionMs = 0L
    private var loadedPlaying = false
    private var nextAttemptId = 0L

    fun nextAttempt(request: PlaybackRequest): PlaybackRequest = request.copy(attemptId = ++nextAttemptId)

    fun accepts(failure: PlaybackFailure): Boolean =
        failure.attemptId == null ||
            active?.let { it.occurrenceId == failure.occurrenceId && it.attemptId == failure.attemptId } == true

    suspend fun prepare(occurrence: ContentOccurrence): Prepared = try {
        val locator = locators.locate(occurrence.copy(item = occurrence.item.canonicalSong()))
        Prepared.Ready(PlaybackRequest(occurrence.occurrenceId, occurrence.item.ref.id, locator))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Prepared.Unresolved(
            PlaybackFailure(
                occurrenceId = occurrence.occurrenceId,
                reason = PlaybackFailureReason.SOURCE_UNAVAILABLE,
                message = error.message.orEmpty(),
                httpStatus = error.httpStatusOrNull(),
                causes = error.playbackCauses(),
            ),
        )
    }

    fun loaded(request: PlaybackRequest, positionMs: Long, playing: Boolean, recovering: Boolean = false) {
        if (!recovering) {
            attempts.clear()
            budgets.clear()
        }
        active = request
        loadedPositionMs = positionMs
        loadedPlaying = playing
    }

    fun unresolved() {
        active = null
        attempts.clear()
        budgets.clear()
    }

    suspend fun recover(failure: PlaybackFailure, occurrence: ContentOccurrence): Load? {
        // Unattributed legacy failures keep their terminal policy, never reopening an unrelated active load.
        if (failure.attemptId == null || !accepts(failure)) return null
        val current = active?.takeIf { it.occurrenceId == occurrence.occurrenceId } ?: return null
        val source = current.locator.source?.takeUnless { it == AudioSourceType.YOUTUBE } ?: return null
        if (current.locator is PlaybackLocator.LocalFile || current.locator is PlaybackLocator.TelegramFile) return null
        // Native engines collapse lease read/expiry failures into DECODE_FAILED, not just OPEN_FAILED.
        // A carried status below 400 was never a rejection, so there is nothing to classify.
        if (failure.reason !in setOf(PlaybackFailureReason.OPEN_FAILED, PlaybackFailureReason.DECODE_FAILED) ||
            failure.httpStatus?.let { it < 400 } == true
        ) return null
        val snapshot = player.snapshot.value?.takeIf { it.occurrenceId == current.occurrenceId }
        val observed = snapshot?.let { player.positionMs() }?.coerceAtLeast(0L)
        // An unsuccessful reopen can report zero before its requested seek is applied.
        val position = observed?.takeUnless { it == 0L && loadedPositionMs > 0L } ?: loadedPositionMs
        val playing = snapshot?.playWhenReady ?: loadedPlaying
        fun recovered(locator: PlaybackLocator): Load {
            // Pause/play remains available while the provider resolves. Never undo a newer user intent.
            val latestIntent = player.snapshot.value?.takeIf { it.occurrenceId == current.occurrenceId }?.playWhenReady
            return Load(current.copy(locator = locator), position, latestIntent ?: playing)
        }
        // The policy classifies the failure and spends one attempt of this song's budget. Thirty seconds
        // of progress since the last failure refunds the budget, so a song that played on is not retired.
        val now = nowMs()
        val mediaId = current.mediaId
        val budget = (budgets[mediaId] ?: PlaybackRecoveryBudget.starting(now))
            .forPlaybackFailure(position, now)
        val decision = PlaybackRecoveryPolicy.decide(PlaybackFailureClassifier.categorize(failure), budget, now)
        var remaining = decision.budget
        budgets[mediaId] = remaining
        when (decision.action) {
            PlaybackRecoveryAction.NONE -> {
                budgets.remove(mediaId)
                return null
            }
            // Same-resource actions keep the resolved locator: a timeout is retried as-is and a network
            // or renderer rejection is reloaded by the engine, never re-resolving a cached URL.
            PlaybackRecoveryAction.RETRY_SAME_URL,
            PlaybackRecoveryAction.INVALIDATE_NETWORK,
            PlaybackRecoveryAction.REBUILD_RENDERER -> return recovered(current.locator)
            else -> Unit
        }
        return try {
            if (attempts.onFailure(current.occurrenceId, source)) {
                if (remaining.requestsRemaining <= 0) return null
                remaining = remaining.consumeRequest()
                locate(occurrence, QueueSourceRecovery(source, attempts.rejectedSources(current.occurrenceId)))?.let {
                    attempts.resolved(current.occurrenceId)
                    return recovered(it)
                }
                attempts.reject(current.occurrenceId, source)
            }
            if (remaining.requestsRemaining <= 0) return null
            remaining = remaining.consumeRequest()
            val next = locate(occurrence, QueueSourceRecovery(rejectedSources = attempts.rejectedSources(current.occurrenceId)))
                ?: return null
            attempts.resolved(current.occurrenceId)
            recovered(next)
        } catch (_: TerminalPlaybackAuthentication) {
            // Authentication that requires user action never buys an anonymous or cross-account fallback.
            null
        } finally {
            budgets[mediaId] = remaining
        }
    }

    private suspend fun locate(occurrence: ContentOccurrence, recovery: QueueSourceRecovery): PlaybackLocator? =
        try {
            locators.recover(occurrence, recovery)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (auth: TerminalPlaybackAuthentication) {
            throw auth
        } catch (_: Exception) {
            null
        }

    class Load(val request: PlaybackRequest, val positionMs: Long, val playWhenReady: Boolean)

    sealed interface Prepared {
        class Ready(val request: PlaybackRequest) : Prepared
        class Unresolved(val failure: PlaybackFailure) : Prepared
    }
}
