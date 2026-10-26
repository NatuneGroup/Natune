/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.recognition

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionCaptureSpec

/**
 * Builds the Shazam DejaVu fingerprint for one recognition window in common code, so iOS and every other target
 * produce the same fingerprint as the desktop host. This is the retained shazamkit generator: the 2048-point window,
 * 128-sample hop, peak spreading, four frequency bands, delta-coded peaks and the 48-byte header with CRC32. It is
 * rewritten with portable Kotlin only (no JVM buffers, CRC or Base64 classes). Any change to the arithmetic, the
 * window or the wire layout changes what the recognition service can match, so each expression keeps the original
 * evaluation order.
 *
 * Feed a complete window with [feedPcm16Mono] before calling [nextSignatureOrNull]. Processing stops once the length
 * and peak-count limits are met or the buffer runs out, so a partial window returns a shorter fingerprint without an
 * error. The header stores the processed sample count, which the matcher reads back as the signed duration, so the
 * encoded length is the real processed length and never the capture length.
 *
 * Every frequency derives from [RecognitionCaptureSpec.SAMPLE_RATE_HZ], and the header's sample-rate id (3) is the
 * Shazam code for 16 kHz. A different capture rate needs both changed together.
 *
 * [checkCancelled] runs before every hop so cancellation stays prompt during this CPU-only loop.
 */
internal class ShazamSignatureGenerator(
    private val maxTimeSeconds: Double = 3.1,
    private val maxPeaks: Int = 255,
    private val checkCancelled: () -> Unit = {},
) {
    private val sampleRateHz: Int = RecognitionCaptureSpec.SAMPLE_RATE_HZ
    private val fft = Fft(WINDOW_SIZE)
    private val window =
        DoubleArray(WINDOW_SIZE) { i ->
            val n = 2050
            val j = i + 1
            0.5 - 0.5 * cos(2.0 * PI * j / (n - 1))
        }
    private val realBuffer = DoubleArray(WINDOW_SIZE)
    private val imagBuffer = DoubleArray(WINDOW_SIZE)
    private val originSpread = DoubleArray(SPECTRUM_BINS)

    private var pending = IntArray(0)
    private var pendingSize = 0
    private var processedSamples = 0

    private val ringSamples = IntArray(WINDOW_SIZE)
    private var ringSamplePos = 0

    private val fftOutputs = Array(HISTORY_LENGTH) { DoubleArray(SPECTRUM_BINS) }
    private var fftPos = 0

    private val spreadOutputs = Array(HISTORY_LENGTH) { DoubleArray(SPECTRUM_BINS) }
    private var spreadPos = 0
    private var spreadWritten = 0

    private var signatureNumberSamples = 0
    private val bandToPeaks = linkedMapOf<FrequencyBand, MutableList<FrequencyPeak>>()

    fun feedPcm16Mono(samples: ShortArray) {
        val required = pendingSize + samples.size
        if (required > pending.size) {
            pending = pending.copyOf(max(required, pending.size * 2))
        }
        for (i in samples.indices) {
            pending[pendingSize + i] = samples[i].toInt()
        }
        pendingSize = required
    }

    fun nextSignatureOrNull(): String? {
        if (pendingSize - processedSamples < HOP_SAMPLES) return null

        while (pendingSize - processedSamples >= HOP_SAMPLES &&
            (
                signatureNumberSamples.toDouble() / sampleRateHz < maxTimeSeconds ||
                    bandToPeaks.values.sumOf { it.size } < maxPeaks
            )
        ) {
            checkCancelled()
            processInput(processedSamples, processedSamples + HOP_SAMPLES)
            processedSamples += HOP_SAMPLES
        }

        if (bandToPeaks.isEmpty()) return null

        val uri = encodeDataUri(signatureNumberSamples)
        reset()
        return uri
    }

    private fun reset() {
        // The captured PCM is dropped with the buffer, so nothing from one window survives into the next call.
        pending = IntArray(0)
        pendingSize = 0
        processedSamples = 0
        ringSamples.fill(0)
        ringSamplePos = 0
        fftPos = 0
        spreadPos = 0
        spreadWritten = 0
        signatureNumberSamples = 0
        bandToPeaks.clear()
    }

    private fun processInput(
        start: Int,
        endExclusive: Int,
    ) {
        signatureNumberSamples += endExclusive - start
        var pos = start
        while (pos < endExclusive) {
            doFft(pos, pos + HOP_SAMPLES)
            doPeakSpreadingAndRecognition()
            pos += HOP_SAMPLES
        }
    }

    private fun doFft(
        start: Int,
        endExclusive: Int,
    ) {
        var i = start
        while (i < endExclusive) {
            ringSamples[ringSamplePos] = pending[i]
            ringSamplePos++
            if (ringSamplePos == ringSamples.size) ringSamplePos = 0
            i++
        }

        var p = ringSamplePos
        var idx = 0
        while (idx < WINDOW_SIZE) {
            if (p == WINDOW_SIZE) p = 0
            realBuffer[idx] = ringSamples[p].toDouble() * window[idx]
            imagBuffer[idx] = 0.0
            p++
            idx++
        }

        fft.transform(realBuffer, imagBuffer)

        val out = fftOutputs[fftPos]
        var k = 0
        while (k <= MAX_SPECTRUM_BIN) {
            val re = realBuffer[k]
            val im = imagBuffer[k]
            val v = (re * re + im * im) / POWER_SCALE
            out[k] = if (v <= POWER_FLOOR) POWER_FLOOR else v
            k++
        }

        fftPos++
        if (fftPos == fftOutputs.size) fftPos = 0
    }

    private fun doPeakSpreadingAndRecognition() {
        doPeakSpreading()
        if (spreadWritten >= RECOGNITION_DELAY) {
            doPeakRecognition()
        }
    }

    private fun doPeakSpreading() {
        val origin = fftOutputs[floorMod(fftPos - 1, fftOutputs.size)]

        // originSpread is fully rewritten on every hop, so one reusable buffer replaces a fresh allocation per hop.
        var i = 0
        while (i <= SPREAD_NEIGHBOR_LIMIT) {
            originSpread[i] = max(origin[i], max(origin[i + 1], origin[i + 2]))
            i++
        }
        for (bin in (SPREAD_NEIGHBOR_LIMIT + 1) until SPECTRUM_BINS) {
            originSpread[bin] = origin[bin]
        }

        val s1 = spreadOutputs[floorMod(spreadPos - 1, spreadOutputs.size)]
        val s2 = spreadOutputs[floorMod(spreadPos - 3, spreadOutputs.size)]
        val s3 = spreadOutputs[floorMod(spreadPos - 6, spreadOutputs.size)]

        for (bin in 0 until SPECTRUM_BINS) {
            val m1 = max(originSpread[bin], s1[bin])
            s1[bin] = m1
            val m2 = max(m1, s2[bin])
            s2[bin] = m2
            val m3 = max(m2, s3[bin])
            s3[bin] = m3
        }

        originSpread.copyInto(spreadOutputs[spreadPos], 0, 0, SPECTRUM_BINS)

        spreadPos++
        if (spreadPos == spreadOutputs.size) spreadPos = 0
        spreadWritten++
    }

    private fun doPeakRecognition() {
        val fftLagged = fftOutputs[floorMod(fftPos - RECOGNITION_DELAY, fftOutputs.size)]
        val spreadLagged = spreadOutputs[floorMod(spreadPos - SPREAD_DELAY, spreadOutputs.size)]

        var bin = MIN_PEAK_BIN
        while (bin <= MAX_PEAK_BIN) {
            val energy = fftLagged[bin]
            if (energy >= ENERGY_FLOOR && energy >= spreadLagged[bin - 1]) {
                var maxNeighbor = 0.0
                for (offset in NEIGHBOR_OFFSETS) {
                    maxNeighbor = max(maxNeighbor, spreadLagged[bin + offset])
                }

                if (energy > maxNeighbor) {
                    var maxTimeNeighbor = maxNeighbor
                    for (offset in TIME_OFFSETS) {
                        val idx = floorMod(spreadPos + offset, spreadOutputs.size)
                        maxTimeNeighbor = max(maxTimeNeighbor, spreadOutputs[idx][bin - 1])
                    }

                    if (energy > maxTimeNeighbor) {
                        recordPeak(fftLagged, bin, energy)
                    }
                }
            }
            bin++
        }
    }

    private fun recordPeak(
        fftLagged: DoubleArray,
        bin: Int,
        energy: Double,
    ) {
        val fftNumber = spreadWritten - RECOGNITION_DELAY
        val peakMagnitude = ln(max(ENERGY_FLOOR, energy)) * LOG_SCALE + LOG_OFFSET
        val peakMagnitudeBefore = ln(max(ENERGY_FLOOR, fftLagged[bin - 1])) * LOG_SCALE + LOG_OFFSET
        val peakMagnitudeAfter = ln(max(ENERGY_FLOOR, fftLagged[bin + 1])) * LOG_SCALE + LOG_OFFSET

        val variation1 = peakMagnitude * 2.0 - peakMagnitudeBefore - peakMagnitudeAfter
        if (variation1 > 0.0) {
            val variation2 = (peakMagnitudeAfter - peakMagnitudeBefore) * VARIATION_SCALE / variation1
            val correctedBin = bin * BIN_SCALE + variation2
            val freqHz = correctedBin * (sampleRateHz / 2.0 / 1024.0 / BIN_SCALE)

            val band = bandFor(freqHz)
            if (band != null) {
                bandToPeaks.getOrPut(band) { mutableListOf() }.add(
                    FrequencyPeak(
                        fftPassNumber = fftNumber,
                        peakMagnitude = peakMagnitude.toInt(),
                        correctedPeakFrequencyBin = correctedBin.toInt(),
                    ),
                )
            }
        }
    }

    private fun bandFor(freqHz: Double): FrequencyBand? =
        when {
            freqHz in 250.0..520.0 -> FrequencyBand.HZ_250_520
            freqHz > 520.0 && freqHz <= 1450.0 -> FrequencyBand.HZ_520_1450
            freqHz > 1450.0 && freqHz <= 3500.0 -> FrequencyBand.HZ_1450_3500
            freqHz > 3500.0 && freqHz <= 5500.0 -> FrequencyBand.HZ_3500_5500
            else -> null
        }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeDataUri(numberSamples: Int): String =
        DATA_URI_PREFIX + Base64.Default.encode(encodeBinary(numberSamples))

    private fun encodeBinary(numberSamples: Int): ByteArray {
        val contents = ByteWriter()

        // Bands are written in declaration order, which is ascending band value; empty bands are omitted.
        for (band in FrequencyBand.entries) {
            val peaks = bandToPeaks[band] ?: continue
            val peaksBytes = ByteWriter()
            var lastFftPass = 0
            for (peak in peaks) {
                var delta = peak.fftPassNumber - lastFftPass
                if (delta >= DELTA_ESCAPE) {
                    peaksBytes.write(0xFF)
                    peaksBytes.writeLittleInt(peak.fftPassNumber)
                    lastFftPass = peak.fftPassNumber
                    delta = 0
                }
                peaksBytes.write(delta.coerceAtLeast(0).coerceAtMost(DELTA_ESCAPE - 1))
                peaksBytes.writeLittleShort(peak.peakMagnitude)
                peaksBytes.writeLittleShort(peak.correctedPeakFrequencyBin)
                lastFftPass = peak.fftPassNumber
            }

            val peaksValue = peaksBytes.toByteArray()
            contents.writeLittleInt(BAND_HEADER_BASE + band.value)
            contents.writeLittleInt(peaksValue.size)
            contents.writeBytes(peaksValue)
            repeat(floorMod(-peaksValue.size, 4)) { contents.write(0) }
        }

        val contentsValue = contents.toByteArray()
        val sizeMinusHeader = contentsValue.size + SECTION_HEADER_SIZE

        // The sample count at byte 40 carries a fixed 0.24 s (3840-sample) offset that the matcher subtracts again.
        val header = ByteWriter(HEADER_SIZE)
        header.writeLittleInt(0xCAFE2580.toInt())
        header.writeLittleInt(0)
        header.writeLittleInt(sizeMinusHeader)
        header.writeLittleInt(0x94119C00.toInt())
        header.writeLittleInt(0)
        header.writeLittleInt(0)
        header.writeLittleInt(0)
        header.writeLittleInt(SAMPLE_RATE_ID shl 27)
        header.writeLittleInt(0)
        header.writeLittleInt(0)
        header.writeLittleInt((numberSamples + sampleRateHz * 0.24).toInt())
        header.writeLittleInt((15 shl 19) + 0x40000)

        val full = ByteWriter(HEADER_SIZE + SECTION_HEADER_SIZE + contentsValue.size)
        full.writeBytes(header.toByteArray())
        full.writeLittleInt(0x40000000)
        full.writeLittleInt(sizeMinusHeader)
        full.writeBytes(contentsValue)

        val withHeader = full.toByteArray()
        val crc = crc32(withHeader, CRC_COVERED_OFFSET, withHeader.size - CRC_COVERED_OFFSET)
        putLittleInt(withHeader, CRC_FIELD_OFFSET, crc)
        return withHeader
    }

    private fun putLittleInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
    }

    private fun crc32(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var crc = -1
        for (i in offset until offset + length) {
            crc = (crc ushr 8) xor CRC_TABLE[(crc xor bytes[i].toInt()) and 0xFF]
        }
        return crc.inv()
    }

    private fun floorMod(
        value: Int,
        modulus: Int,
    ): Int {
        val remainder = value % modulus
        return if (remainder < 0) remainder + modulus else remainder
    }

    private enum class FrequencyBand(
        val value: Int,
    ) {
        HZ_250_520(0),
        HZ_520_1450(1),
        HZ_1450_3500(2),
        HZ_3500_5500(3),
    }

    private data class FrequencyPeak(
        val fftPassNumber: Int,
        val peakMagnitude: Int,
        val correctedPeakFrequencyBin: Int,
    )

    private class ByteWriter(
        initialCapacity: Int = 256,
    ) {
        private var buffer = ByteArray(initialCapacity)
        private var size = 0

        fun write(value: Int) {
            ensureCapacity(size + 1)
            buffer[size] = value.toByte()
            size++
        }

        fun writeBytes(bytes: ByteArray) {
            ensureCapacity(size + bytes.size)
            bytes.copyInto(buffer, size)
            size += bytes.size
        }

        fun writeLittleShort(value: Int) {
            write(value)
            write(value shr 8)
        }

        fun writeLittleInt(value: Int) {
            write(value)
            write(value shr 8)
            write(value shr 16)
            write(value shr 24)
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required > buffer.size) {
                buffer = buffer.copyOf(max(required, buffer.size * 2))
            }
        }
    }

    private class Fft(
        private val n: Int,
    ) {
        private val bits: Int =
            run {
                var count = 0
                var remaining = n
                while (remaining > 1) {
                    remaining = remaining shr 1
                    count++
                }
                count
            }
        private val cosTable = DoubleArray(n / 2) { i -> cos(2.0 * PI * i / n) }
        private val sinTable = DoubleArray(n / 2) { i -> sin(2.0 * PI * i / n) }
        private val bitReversal = IntArray(n) { i -> reverseBits(i, bits) }

        fun transform(
            real: DoubleArray,
            imag: DoubleArray,
        ) {
            for (i in 0 until n) {
                val j = bitReversal[i]
                if (j > i) {
                    val tr = real[i]
                    real[i] = real[j]
                    real[j] = tr
                    val ti = imag[i]
                    imag[i] = imag[j]
                    imag[j] = ti
                }
            }

            var len = 2
            while (len <= n) {
                val halfLen = len / 2
                val tableStep = n / len
                var i = 0
                while (i < n) {
                    var j = 0
                    var k = 0
                    while (j < halfLen) {
                        val cosValue = cosTable[k]
                        val sinValue = sinTable[k]
                        val i1 = i + j
                        val i2 = i1 + halfLen

                        val r2 = real[i2]
                        val im2 = imag[i2]
                        val tpre = r2 * cosValue + im2 * sinValue
                        val tpim = -r2 * sinValue + im2 * cosValue

                        real[i2] = real[i1] - tpre
                        imag[i2] = imag[i1] - tpim
                        real[i1] += tpre
                        imag[i1] += tpim

                        j++
                        k += tableStep
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        private fun reverseBits(
            value: Int,
            bitCount: Int,
        ): Int {
            var x = value
            var y = 0
            repeat(bitCount) {
                y = (y shl 1) or (x and 1)
                x = x ushr 1
            }
            return y
        }
    }

    private companion object {
        const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"
        const val WINDOW_SIZE = 2048
        const val HOP_SAMPLES = 128
        const val SPECTRUM_BINS = 1025
        const val MAX_SPECTRUM_BIN = 1024
        const val HISTORY_LENGTH = 256
        const val RECOGNITION_DELAY = 46
        const val SPREAD_DELAY = 49
        const val SPREAD_NEIGHBOR_LIMIT = 1021
        const val MIN_PEAK_BIN = 10
        const val MAX_PEAK_BIN = 1014
        const val ENERGY_FLOOR = 1.0 / 64.0
        const val POWER_FLOOR = 1e-10
        const val POWER_SCALE = 131072.0
        const val LOG_SCALE = 1477.3
        const val LOG_OFFSET = 6144.0
        const val VARIATION_SCALE = 32.0
        const val BIN_SCALE = 64.0
        const val DELTA_ESCAPE = 255
        const val BAND_HEADER_BASE = 0x60030040
        const val SAMPLE_RATE_ID = 3
        const val HEADER_SIZE = 48
        const val SECTION_HEADER_SIZE = 8
        const val CRC_FIELD_OFFSET = 4
        const val CRC_COVERED_OFFSET = 8

        val NEIGHBOR_OFFSETS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        val TIME_OFFSETS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

        // Standard reflected CRC-32 (polynomial 0xEDB88320), the same checksum the retained generator takes from java.util.zip.
        val CRC_TABLE: IntArray =
            IntArray(256) { index ->
                var value = index
                repeat(8) {
                    value =
                        if ((value and 1) != 0) {
                            (value ushr 1) xor 0xEDB88320.toInt()
                        } else {
                            value ushr 1
                        }
                }
                value
            }
    }
}
