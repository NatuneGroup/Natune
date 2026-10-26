/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.nativeaudio.at_audio_session_begin_recording
import moe.rukamori.archivetune.nativeaudio.at_audio_session_end_recording
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionCapturePort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionCaptureSpec
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionPermissionPort
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.Foundation.NSLock
import platform.Foundation.NSNotificationCenter

private const val CLOSED_MESSAGE = "microphone capture is closed"

/**
 * Slack past the recording window for engine start and the first buffer. A window still unfilled after this has no
 * working input, so it fails instead of waiting on the microphone indefinitely.
 */
private const val START_GRACE_MILLIS = 4_000L

/** Samples in one window, derived from the shared spec so every platform fingerprints the same length. */
private val CAPTURE_SAMPLE_COUNT: Int =
    (RecognitionCaptureSpec.SAMPLE_RATE_HZ.toLong() * RecognitionCaptureSpec.DURATION_MILLIS / 1_000L).toInt()

private const val INT16_FULL_SCALE = 32_768.0
private const val PCM_FULL_SCALE = 32_767.0

private inline fun <T> NSLock.withRecognitionLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/**
 * Microphone capture for the recognition owner on the iOS host. It records exactly the window in
 * [RecognitionCaptureSpec], so the fingerprint input matches every other platform.
 *
 * Each window owns its own [AVAudioEngine], tap and notification observers, and tears all of them down before
 * [captureSamples] returns or throws. Only one window runs at a time: a second request fails at once, and [close]
 * fails an open window and makes later calls refuse. The adapter never pads, synthesizes audio, writes samples to
 * disk or logs them.
 *
 * The audio session is shared with playback in this process, so a window claims it through the native session policy in
 * `ATNativeAudio.h` instead of switching it directly. The window keeps play-and-record while it is open. Playback started
 * inside the window keeps that category, and the last release restores the category for the current playback intent, or
 * the one from before the window. The session is never deactivated here, because the playback host may be using it.
 */
class IosRecognitionCapture : RecognitionPermissionPort, RecognitionCapturePort {
    private val stateLock = NSLock()
    private var activeCapture: CaptureRun? = null
    private var closed = false

    override suspend fun requestMicrophonePermission(): Boolean {
        check(!isClosed()) { CLOSED_MESSAGE }
        val session = AVAudioSession.sharedInstance()
        return when (session.recordPermission) {
            AVAudioSessionRecordPermissionGranted -> true
            AVAudioSessionRecordPermissionDenied -> false
            else ->
                // The prompt is system UI: it is requested on the main thread, and its answer resumes this coroutine.
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        session.requestRecordPermission { granted -> continuation.resume(granted) }
                    }
                }
        }
    }

    override suspend fun captureSamples(): ShortArray {
        val capture = claimCapture()
        try {
            withContext(Dispatchers.Default) { startCapture(capture) }
            return withTimeoutOrNull(capture.deadlineMillis) { capture.result.await() }
                ?: throw IllegalStateException("microphone capture timed out")
        } finally {
            // Teardown leaves the caller's dispatcher, and NonCancellable keeps cancellation from skipping it.
            withContext(NonCancellable + Dispatchers.Default) { releaseCapture(capture) }
        }
    }

    /** Fails an open window and makes later calls refuse. The owning host calls it once on shutdown. Safe to repeat. */
    fun close() {
        val running = stateLock.withRecognitionLock {
            if (closed) {
                null
            } else {
                closed = true
                activeCapture
            }
        }
        running?.fail(IllegalStateException(CLOSED_MESSAGE))
    }

    private fun isClosed(): Boolean = stateLock.withRecognitionLock { closed }

    private fun claimCapture(): CaptureRun =
        stateLock.withRecognitionLock {
            check(!closed) { CLOSED_MESSAGE }
            check(activeCapture == null) { "a microphone capture is already running" }
            CaptureRun().also { activeCapture = it }
        }

    private fun releaseCapture(capture: CaptureRun) {
        // The window stays claimed until its native objects are gone, so a new window cannot start over them.
        capture.teardown()
        stateLock.withRecognitionLock {
            if (activeCapture === capture) activeCapture = null
        }
    }

    private fun startCapture(capture: CaptureRun) {
        capture.ensureRunning()
        val session = AVAudioSession.sharedInstance()
        check(session.recordPermission == AVAudioSessionRecordPermissionGranted) {
            "microphone permission is not granted"
        }
        // The native policy switches the shared session for this window and keeps playback's category intent. The claim
        // is released exactly once by teardown, whichever way the window ends.
        capture.claimRecordingSession()
        check(session.inputAvailable) { "no microphone input is available" }

        val engine = AVAudioEngine()
        capture.attach(engine)
        val input = engine.inputNode
        // The tap uses the hardware format; CaptureRun converts it, since a tap in another format may be rejected.
        val format = input.outputFormatForBus(0uL)
        check(format.sampleRate > 0.0 && format.channelCount > 0u) { "microphone input format is unusable" }
        capture.configure(format.sampleRate)
        input.installTapOnBus(0uL, 4_096u, format) { buffer, _ ->
            if (buffer != null) capture.ingest(buffer)
        }
        engine.prepare()
        check(engine.startAndReturnError(null)) { "audio input could not start" }
        // Observed only after a successful start, so the engine's own start is not mistaken for a route change.
        capture.observeFailures(engine)
    }
}

/**
 * One recording window. Its engine, tap, observers and session change exist only between the adapter's claim and
 * [teardown]. The first of a full window, [fail] or teardown ends the window; audio arriving after that is dropped.
 */
private class CaptureRun {
    val deadlineMillis: Long = RecognitionCaptureSpec.DURATION_MILLIS + START_GRACE_MILLIS
    val result: CompletableDeferred<ShortArray> = CompletableDeferred()

    private val lock = NSLock()
    private val samples = ShortArray(CAPTURE_SAMPLE_COUNT)
    private var filled = 0
    private var audible = false
    private var finished = false
    private var tornDown = false
    private var inputFramesPerSample = 1.0
    private var windowSpan = 0.0
    private var windowSum = 0.0

    // Touched only by the coroutine that starts and tears down this window, never by the audio thread.
    private var engine: AVAudioEngine? = null
    private val observers = mutableListOf<Any>()
    private var recordingClaimed = false

    fun ensureRunning() {
        check(lock.withRecognitionLock { !finished }) { "microphone capture ended before recording started" }
    }

    fun configure(inputSampleRateHz: Double) {
        lock.withRecognitionLock {
            inputFramesPerSample = inputSampleRateHz / RecognitionCaptureSpec.SAMPLE_RATE_HZ.toDouble()
        }
    }

    /**
     * Claims the shared session for this window through the native policy, and throws when the session refuses. A claim
     * that returns is released once by [teardown], so a window that fails after this point still gives it back.
     */
    fun claimRecordingSession() {
        when (at_audio_session_begin_recording()) {
            0 -> {
                recordingClaimed = true
            }
            1 -> error("audio session could not switch to recording")
            else -> error("audio session could not be activated for recording")
        }
    }

    fun attach(engine: AVAudioEngine) {
        this.engine = engine
    }

    fun observeFailures(engine: AVAudioEngine) {
        val center = NSNotificationCenter.defaultCenter
        val session = AVAudioSession.sharedInstance()
        // Only this engine's configuration changes count; playback engines in this process must not fail a window.
        observers += center.addObserverForName(AVAudioEngineConfigurationChangeNotification, engine, null) { _ ->
            fail(IllegalStateException("microphone input configuration changed"))
        }
        observers += center.addObserverForName(AVAudioSessionInterruptionNotification, session, null) { _ ->
            fail(IllegalStateException("microphone capture was interrupted"))
        }
        observers += center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, session, null) { _ ->
            fail(IllegalStateException("audio services were reset during capture"))
        }
    }

    /** Audio-thread entry point: converts each buffer to mono PCM16 at the spec rate and ends a full window. */
    fun ingest(buffer: AVAudioPCMBuffer) {
        val frames = buffer.frameLength.toInt()
        val channels = buffer.format.channelCount.toInt()
        if (frames <= 0 || channels <= 0) return
        val floatChannels = buffer.floatChannelData
        val pcmChannels = buffer.int16ChannelData
        if (floatChannels == null && pcmChannels == null) {
            fail(IllegalStateException("microphone buffer layout is unsupported"))
            return
        }
        val outcome = lock.withRecognitionLock {
            if (finished) return
            for (frame in 0 until frames) {
                var mono = 0.0
                for (channel in 0 until channels) {
                    mono += if (floatChannels != null) {
                        floatChannels[channel]!![frame].toDouble()
                    } else {
                        pcmChannels!![channel]!![frame] / INT16_FULL_SCALE
                    }
                }
                offer(mono / channels)
                if (filled == CAPTURE_SAMPLE_COUNT) break
            }
            if (filled < CAPTURE_SAMPLE_COUNT) {
                null
            } else {
                finished = true
                audible
            }
        }
        // Completion happens outside the lock, so a resumed waiter can never re-enter it.
        if (outcome == true) {
            result.complete(samples)
        } else if (outcome == false) {
            result.completeExceptionally(IllegalStateException("microphone delivered no audio"))
        }
    }

    /**
     * Box-filter resampling: each output sample is the mean of the input span it covers, which averages the signal
     * before decimation instead of folding everything above the output Nyquist back into the window. Caller holds [lock].
     */
    private fun offer(sample: Double) {
        var remaining = 1.0
        while (remaining > 0.0 && filled < CAPTURE_SAMPLE_COUNT) {
            val room = inputFramesPerSample - windowSpan
            if (remaining < room) {
                windowSum += sample * remaining
                windowSpan += remaining
                remaining = 0.0
            } else {
                windowSum += sample * room
                emit(windowSum / inputFramesPerSample)
                windowSum = 0.0
                windowSpan = 0.0
                remaining -= room
            }
        }
    }

    /** Caller holds [lock]. An all-zero window is reported as a failure, since a dead input delivers exact silence. */
    private fun emit(mean: Double) {
        val clamped = mean.coerceIn(-1.0, 1.0)
        val pcm = (clamped * PCM_FULL_SCALE).roundToInt().toShort()
        if (pcm.toInt() != 0) audible = true
        samples[filled] = pcm
        filled += 1
    }

    /** Ends the window with [error] unless it already ended. Safe from any thread. */
    fun fail(error: Throwable) {
        val first = lock.withRecognitionLock {
            if (finished) {
                false
            } else {
                finished = true
                true
            }
        }
        if (first) result.completeExceptionally(error)
    }

    /** Releases every native object this window created. Idempotent. Runs off the main thread. */
    fun teardown() {
        val alreadyTornDown = lock.withRecognitionLock {
            val was = tornDown
            tornDown = true
            finished = true
            was
        }
        if (alreadyTornDown) return
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        val running = engine
        engine = null
        if (running != null) {
            running.inputNode.removeTapOnBus(0uL)
            running.stop()
        }
        if (recordingClaimed) {
            recordingClaimed = false
            // The native policy restores the category for the current playback intent, or the one from before the window.
            at_audio_session_end_recording()
        }
        result.completeExceptionally(IllegalStateException("microphone capture ended"))
    }
}
