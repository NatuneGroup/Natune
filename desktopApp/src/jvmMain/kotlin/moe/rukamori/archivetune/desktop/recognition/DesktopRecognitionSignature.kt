package moe.rukamori.archivetune.desktop.recognition

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionCaptureSpec

/**
 * Builds the fingerprint for one captured recognition window on the desktop host. The work is CPU-only over PCM the
 * capture already holds: nothing is written to disk, sent over the network or kept after the call returns.
 *
 * The buffer must be exactly the length the capture contract records. A shorter or longer buffer would fingerprint
 * partial or padded audio, so it yields null, and so does audio with no spectral peaks. Null is the honest "no
 * fingerprint" outcome; callers must not substitute a guessed or silent window to get a result.
 */
class DesktopRecognitionSignature {
    suspend fun generateSignature(samples: ShortArray): ShazamSignature? {
        if (samples.size != WINDOW_SAMPLE_COUNT) return null
        coroutineContext.ensureActive()

        val signature =
            withContext(Dispatchers.Default) {
                val generator = ShazamSignatureGenerator()
                generator.feedPcm16Mono(samples)
                generator.nextSignatureOrNull()
            }

        coroutineContext.ensureActive()
        return signature
    }

    private companion object {
        val WINDOW_SAMPLE_COUNT: Int =
            (RecognitionCaptureSpec.SAMPLE_RATE_HZ.toLong() * RecognitionCaptureSpec.DURATION_MILLIS / 1000L).toInt()
    }
}
