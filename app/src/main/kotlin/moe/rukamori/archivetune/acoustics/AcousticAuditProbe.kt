@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.acoustics

import android.content.Context
import android.system.ErrnoException
import androidx.media3.datasource.cache.Cache
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditTarget
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticRun
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticUnavailable
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticWindow

/**
 * The Android half of the shared acoustic audit: it resolves the audited source, decodes one bounded
 * source-rate PCM window from it, and hands the shared owner an [AcousticWindow]. Only the platform reads,
 * their budgets and their cancellation live here; the state machine, the FFT and the verdict are shared.
 *
 * Reads block by nature (MediaCodec, RandomAccessFile, an OkHttp range read), so they run on the bounded
 * worker pool while the run awaits them: cancelling closes the owned source, which is what unblocks a read
 * already inside a provider or a socket, and the run waits for that read to unwind before the worker
 * permit returns. Two reads may run at once across every audit surface; a third start is refused as
 * `analysis_busy` instead of queueing behind a slow provider.
 */
internal class AcousticAuditProbe(
    context: Context,
    caches: List<Cache>,
    private val snapshot: (String) -> AcousticSourceSnapshot?,
) {
    private val resolver = AcousticAuditSourceResolver(context, caches, snapshot)

    /** The input the last completed read decoded from, for the owner's post-analysis re-check. */
    private val decoded = AtomicReference<AcousticInput?>(null)

    /**
     * The refusal code of the live source re-check, or null while the decoded rendition is still the
     * player's. A local file or content audit carries no snapshot and stays current; a remote audit that
     * was re-resolved, expired or replaced reports `source_changed` instead of stale evidence.
     */
    suspend fun verifySource(): String? = withContext(Dispatchers.Main.immediate) {
        val input = decoded.get() ?: return@withContext null
        if (resolver.isCurrent(input)) null else "source_changed"
    }

    suspend fun window(target: AcousticAuditTarget, run: AcousticRun): AcousticWindow {
        if (!AcousticWorkers.permits.tryAcquire()) return AcousticWindow.Refused("analysis_busy")
        try {
            return read(target, run)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AcousticUnavailable) {
            throw error
        } catch (_: IOException) {
            throw AcousticUnavailable("input_unreadable")
        } catch (_: ErrnoException) {
            throw AcousticUnavailable("local_unreadable")
        } catch (_: SecurityException) {
            throw AcousticUnavailable("input_permission_denied")
        } finally {
            AcousticWorkers.permits.release()
        }
    }

    private suspend fun read(target: AcousticAuditTarget, run: AcousticRun): AcousticWindow {
        val work = AcousticWork(run = run::check)
        try {
            val input = resolver.resolve(target, work).also { work.own(it) }
            decoded.set(input)
            return coroutineScope {
                val reading = async(AcousticWorkers.dispatcher) { decode(input, target, run, work) }
                try {
                    reading.await()
                } catch (cancelled: CancellationException) {
                    work.close()
                    withContext(NonCancellable) { reading.join() }
                    throw cancelled
                }
            }
        } finally {
            work.close()
        }
    }

    private fun decode(
        input: AcousticInput,
        target: AcousticAuditTarget,
        run: AcousticRun,
        work: AcousticWork,
    ): AcousticWindow {
        run.progress(0f)
        val pcm = AcousticAuditDecoder.decode(input, work) { fraction -> run.progress(fraction) }
        // Android binds an audit to the song it was opened for: the media id is this host's request
        // identity, and the live snapshot re-check above carries the per-rendition binding.
        return AcousticWindow.Decoded(
            pcm = pcm,
            sourceLabel = input.label,
            advertisedSampleRate = input.advertisedRate,
            claimedLossless = input.claimedLossless,
            limitations = input.limitations,
            requestKey = target.mediaId,
            occurrenceKey = target.mediaId,
        )
    }
}
