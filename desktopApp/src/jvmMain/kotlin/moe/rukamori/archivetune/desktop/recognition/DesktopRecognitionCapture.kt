/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.recognition

import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionCapturePort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionCaptureSpec
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionDiagnostics
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionPermissionPort

/**
 * The desktop microphone behind the recognition owner. One route-owned instance answers the permission check and
 * records the fingerprint window, and both use the same Java Sound input line. Java Sound is part of the JDK's
 * java.desktop module, so no recorder process, native library or temporary file is involved.
 *
 * The permission check opens the real line and keeps it for the next capture. A grant that was not backed by an open
 * device would say nothing about whether capture can succeed. Opening does not start the stream, so no audio is read
 * before [captureSamples] runs.
 *
 * Calls must come from one owner. An overlapping call fails rather than sharing the device, and [close] is the final
 * release on route disposal.
 */
class DesktopRecognitionCapture(
    private val diagnostics: RecognitionDiagnostics,
) : RecognitionCapturePort, RecognitionPermissionPort, AutoCloseable {
    private val lock = Any()

    // The one line this adapter holds: kept after a permission check, or the line of the capture in progress.
    private var heldLine: TargetDataLine? = null
    private var operationActive = false
    private var closed = false

    /**
     * True when the recognition input line opened and is kept for the next capture. False when the system has no input
     * line that accepts the recognition format, or the line is unavailable (for example busy or refused); the cause goes
     * to diagnostics. Platform names and format support alone never produce true.
     *
     * Throws IllegalStateException when this adapter is closed or another call is still running.
     */
    override suspend fun requestMicrophonePermission(): Boolean {
        claimOperation()
        try {
            return withContext(Dispatchers.IO) { ensureRetainedLine() }
        } catch (cancelled: CancellationException) {
            releaseHeldLine()
            throw cancelled
        } finally {
            releaseOperation()
        }
    }

    /**
     * Records exactly [RecognitionCaptureSpec.DURATION_MILLIS] of mono PCM16 at [RecognitionCaptureSpec.SAMPLE_RATE_HZ],
     * reading from the retained line or from a freshly opened one. Partial reads are accumulated and never padded.
     *
     * Throws IOException when the input cannot be opened or stops before the window is full, so a short capture is never
     * reported as complete. A device that delivers nothing counts as stopped and throws too, so the empty-array outcome of
     * the port is not produced. Cancellation releases the device and then propagates.
     */
    override suspend fun captureSamples(): ShortArray {
        claimOperation()
        try {
            val input = try {
                withContext(Dispatchers.IO) { heldOpenLine() ?: openIntoHeldSlot() }
            } catch (unavailable: LineUnavailableException) {
                throw IOException(UNAVAILABLE_MESSAGE, unavailable)
            } catch (unsupported: IllegalArgumentException) {
                throw IOException(UNSUPPORTED_MESSAGE, unsupported)
            }
            return recordSamples(input)
        } finally {
            try {
                releaseHeldLine()
            } finally {
                releaseOperation()
            }
        }
    }

    /**
     * Final release for route disposal. Stops and closes the held line, which also ends a running capture early instead of
     * letting it wait out the window. Repeated calls do nothing.
     */
    override fun close() {
        val held = synchronized(lock) {
            if (closed) {
                null
            } else {
                closed = true
                heldLine.also { heldLine = null }
            }
        }
        held?.let { haltAndClose(it) }
    }

    private fun claimOperation() {
        synchronized(lock) {
            check(!closed) { "The microphone capture adapter is closed" }
            check(!operationActive) { "A microphone permission check or capture is already running" }
            operationActive = true
        }
    }

    private fun releaseOperation() {
        synchronized(lock) { operationActive = false }
    }

    private fun ensureRetainedLine(): Boolean {
        if (heldOpenLine() != null) return true
        return try {
            openIntoHeldSlot()
            true
        } catch (unavailable: LineUnavailableException) {
            diagnostics.report(UNAVAILABLE_MESSAGE, unavailable)
            false
        } catch (unsupported: IllegalArgumentException) {
            diagnostics.report(UNSUPPORTED_MESSAGE, unsupported)
            false
        }
    }

    // A retained line the device dropped is closed and forgotten, so the next attempt opens a fresh one.
    private fun heldOpenLine(): TargetDataLine? {
        val current = synchronized(lock) { heldLine } ?: return null
        if (current.isOpen) return current
        synchronized(lock) { heldLine = if (heldLine === current) null else heldLine }
        haltAndClose(current)
        return null
    }

    private fun openIntoHeldSlot(): TargetDataLine {
        val opened = openRecordingLine()
        synchronized(lock) {
            if (closed) {
                haltAndClose(opened)
                throw IllegalStateException("The microphone capture adapter was closed while its input was opening")
            }
            // The busy guard and heldOpenLine() leave the slot empty here, so this never replaces a live line.
            heldLine = opened
        }
        return opened
    }

    // Closing happens before the operation is released, so a following attempt never overlaps a device still closing.
    private suspend fun releaseHeldLine() {
        val held = synchronized(lock) { heldLine.also { heldLine = null } } ?: return
        withContext(NonCancellable + Dispatchers.IO) { haltAndClose(held) }
    }

    private suspend fun recordSamples(input: TargetDataLine): ShortArray = coroutineScope {
        // The JDK read blocks until the window is full and ignores coroutine cancellation. Stopping the line is what
        // lets it return early, so this child performs that release whenever the capture is cancelled.
        val release = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                haltAndClose(input)
            }
        }
        try {
            val bytes = try {
                withContext(Dispatchers.IO) { readCaptureBytes(input) }
            } catch (failure: Throwable) {
                // A cancelled capture reports the cancellation, not the short read that the release caused.
                ensureActive()
                throw failure
            }
            ensureActive()
            decodeLittleEndian(bytes)
        } finally {
            release.cancel()
        }
    }

    private fun readCaptureBytes(input: TargetDataLine): ByteArray {
        val bytes = ByteArray(CAPTURE_BYTES)
        input.start()
        var filled = 0
        while (filled < bytes.size) {
            // A read returns short only when the line was stopped or closed, so zero means the device ended the window.
            val read = input.read(bytes, filled, bytes.size - filled)
            if (read <= 0) throw IOException("The microphone stopped after $filled of ${bytes.size} bytes")
            filled += read
        }
        return bytes
    }

    private fun decodeLittleEndian(bytes: ByteArray): ShortArray {
        val samples = ShortArray(bytes.size / FRAME_BYTES)
        for (index in samples.indices) {
            val offset = index * FRAME_BYTES
            val low = bytes[offset].toInt() and 0xFF
            val high = bytes[offset + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort()
        }
        return samples
    }

    // stop() ends a blocked read with the bytes read so far; close() frees the device. A closed line has nothing to stop.
    private fun haltAndClose(target: TargetDataLine) {
        try {
            if (target.isOpen) target.stop()
        } finally {
            target.close()
        }
    }

    private fun openRecordingLine(): TargetDataLine {
        val input = AudioSystem.getTargetDataLine(RECORDING_FORMAT)
        try {
            input.open(RECORDING_FORMAT, BUFFER_BYTES)
        } catch (failure: Throwable) {
            input.close()
            throw failure
        }
        return input
    }

    private companion object {
        const val FRAME_BYTES = 2
        const val UNAVAILABLE_MESSAGE = "The microphone input is unavailable"
        val UNSUPPORTED_MESSAGE = "No microphone input accepts ${RecognitionCaptureSpec.SAMPLE_RATE_HZ} Hz mono PCM16"

        // Only the recognition window is kept: the fingerprint input has to match the shared spec exactly.
        val SAMPLE_COUNT =
            (RecognitionCaptureSpec.SAMPLE_RATE_HZ.toLong() * RecognitionCaptureSpec.DURATION_MILLIS / 1000L).toInt()
        val CAPTURE_BYTES = SAMPLE_COUNT * FRAME_BYTES

        // One second of device buffer absorbs scheduling jitter while the reader waits on the IO dispatcher.
        val BUFFER_BYTES = RecognitionCaptureSpec.SAMPLE_RATE_HZ * FRAME_BYTES

        // Java Sound does not resample, so a device that cannot record this exact format is reported as unavailable.
        val RECORDING_FORMAT = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            RecognitionCaptureSpec.SAMPLE_RATE_HZ.toFloat(),
            16,
            1,
            FRAME_BYTES,
            RecognitionCaptureSpec.SAMPLE_RATE_HZ.toFloat(),
            false,
        )
    }
}
