package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.playback.playbackCauses
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.QueueSourceRecovery
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication
import moe.rukamori.archivetune.shared.domain.songmetadata.canonicalSong
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.StreamRecoveryTracker

/** Queue-lock confined. A recovered load keeps the same occurrence, user intent and audible position. */
internal class QueueStreamRecovery(private val player: MusicPlayerController, private val locators: QueueLocatorResolver) {
    private val attempts = StreamRecoveryTracker<AudioSourceType>()
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
        if (!recovering) attempts.clear()
        active = request
        loadedPositionMs = positionMs
        loadedPlaying = playing
    }

    fun unresolved() {
        active = null
        attempts.clear()
    }

    suspend fun recover(failure: PlaybackFailure, occurrence: ContentOccurrence): Load? {
        // Unattributed legacy failures keep their terminal policy, never reopening an unrelated active load.
        if (failure.attemptId == null || !accepts(failure)) return null
        val current = active?.takeIf { it.occurrenceId == occurrence.occurrenceId } ?: return null
        val source = current.locator.source?.takeUnless { it == AudioSourceType.YOUTUBE } ?: return null
        if (current.locator is PlaybackLocator.LocalFile || current.locator is PlaybackLocator.TelegramFile) return null
        // Native engines collapse lease read/expiry failures into DECODE_FAILED, not just OPEN_FAILED.
        if (failure.reason !in setOf(PlaybackFailureReason.OPEN_FAILED, PlaybackFailureReason.DECODE_FAILED) ||
            failure.httpStatus?.let { it !in HTTP_REJECTIONS } == true
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
        return try {
            if (attempts.onFailure(current.occurrenceId, source)) {
                locate(occurrence, QueueSourceRecovery(source, attempts.rejectedSources(current.occurrenceId)))?.let {
                    attempts.resolved(current.occurrenceId)
                    return recovered(it)
                }
                attempts.reject(current.occurrenceId, source)
            }
            val next = locate(occurrence, QueueSourceRecovery(rejectedSources = attempts.rejectedSources(current.occurrenceId)))
                ?: return null
            attempts.resolved(current.occurrenceId)
            recovered(next)
        } catch (_: TerminalPlaybackAuthentication) {
            // Authentication that requires user action never buys an anonymous or cross-account fallback.
            null
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

    private companion object {
        val HTTP_REJECTIONS = setOf(401, 403, 404, 410, 416)
    }
}
