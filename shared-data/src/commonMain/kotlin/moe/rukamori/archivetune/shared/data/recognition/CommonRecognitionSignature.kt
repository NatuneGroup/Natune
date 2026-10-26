package moe.rukamori.archivetune.shared.data.recognition

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionCaptureSpec
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionSignaturePort

/**
 * Portable fingerprint producer for [RecognitionSignaturePort]. It is the common signature path for targets without
 * the JVM desktop generator, such as iOS, and returns only the DejaVu data URI. The matcher reads the signed duration
 * from that header, so no separate timing value crosses this boundary.
 *
 * The buffer must be exactly the length the capture spec records. A shorter or longer buffer would fingerprint partial
 * or padded audio, so it yields null, and so does audio with no spectral peaks. Null is the honest "no fingerprint"
 * outcome; callers must not substitute a guessed or silent window. The CPU-only work runs on [dispatcher] with
 * cancellation checked before every hop. The PCM is copied into a per-call generator and dropped when the call
 * returns; nothing is written to disk, logged or retained.
 */
class CommonRecognitionSignature(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RecognitionSignaturePort {
    override suspend fun generateSignature(samples: ShortArray): String? {
        if (samples.size != WINDOW_SAMPLE_COUNT) return null
        currentCoroutineContext().ensureActive()

        val uri =
            withContext(dispatcher) {
                val generator = ShazamSignatureGenerator(checkCancelled = { ensureActive() })
                generator.feedPcm16Mono(samples)
                generator.nextSignatureOrNull()
            }

        currentCoroutineContext().ensureActive()
        return uri
    }

    private companion object {
        val WINDOW_SAMPLE_COUNT: Int =
            (RecognitionCaptureSpec.SAMPLE_RATE_HZ.toLong() * RecognitionCaptureSpec.DURATION_MILLIS / 1000L).toInt()
    }
}
