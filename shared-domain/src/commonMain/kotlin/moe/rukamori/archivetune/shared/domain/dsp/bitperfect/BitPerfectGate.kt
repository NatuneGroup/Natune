package moe.rukamori.archivetune.shared.domain.dsp.bitperfect

/**
 * What the platform adapter observed about the output route for one track. The adapter reports facts and
 * never decides bypass itself; only [BitPerfectGate] does.
 */
data class BitPerfectRouteFacts(
    val bitPerfectRequested: Boolean,
    /** Any software stage that would alter samples is engaged: EQ, normalization, spatial, or tempo/pitch. */
    val softwareDspEngaged: Boolean,
    val usbExclusive: Boolean,
    /** The platform could answer whether the route plays this format directly. */
    val directPlaybackQueried: Boolean,
    val directPlaybackSupported: Boolean,
    val inputSampleRateHz: Int,
    val inputBitDepth: Int,
    /** Rate the route actually runs at. Zero means unknown, which is treated as the input rate. */
    val outputSampleRateHz: Int = 0,
    val effectiveVolume: Float = 1f,
    /** Clock rate latched from a USB exclusive route, or zero when it is not latched. */
    val usbClockRateHz: Int = 0,
    val usbTransportBits: Int = 0,
)

data class BitPerfectDecision(
    val requested: Boolean,
    /** The software chain, mixing and resampling are bypassed and PCM passes through untouched. */
    val bypass: Boolean,
    /** The route may be reported as bit-perfect for this track. Never implied by a request alone. */
    val verifiedBitPerfect: Boolean,
    val resamplerActive: Boolean,
    val softwareVolumeActive: Boolean,
    val failureReason: String?,
)

/**
 * Bypass is conditional. It needs an explicit request, no software stage that alters samples, a direct
 * route that plays this format, and an output rate equal to the input rate. Anything else keeps the
 * software chain and reports why.
 */
object BitPerfectGate {
    fun evaluate(facts: BitPerfectRouteFacts): BitPerfectDecision {
        require(facts.inputSampleRateHz > 0) { "inputSampleRateHz must be positive" }
        require(facts.inputBitDepth > 0) { "inputBitDepth must be positive" }
        val outputRateHz = if (facts.outputSampleRateHz > 0) facts.outputSampleRateHz else facts.inputSampleRateHz
        val resamplerActive = outputRateHz != facts.inputSampleRateHz

        var failure: String? = null
        var direct = false
        var usbRouteVerified = false
        if (facts.bitPerfectRequested) {
            if (facts.usbExclusive) {
                direct = true
                usbRouteVerified = facts.usbClockRateHz == facts.inputSampleRateHz &&
                    facts.usbTransportBits >= facts.inputBitDepth
                if (!usbRouteVerified && facts.usbClockRateHz > 0) {
                    failure = if (facts.usbClockRateHz != facts.inputSampleRateHz) {
                        "USB clock locked at ${facts.usbClockRateHz}Hz"
                    } else {
                        "USB transport carries ${facts.usbTransportBits}bit"
                    }
                }
            } else if (!facts.directPlaybackQueried) {
                failure = "direct playback support could not be queried"
            } else {
                direct = facts.directPlaybackSupported
            }
        }

        val bypass = facts.bitPerfectRequested && !facts.softwareDspEngaged && direct && !resamplerActive
        val softwareVolumeActive = bypass && facts.effectiveVolume != 1f
        // A USB exclusive claim needs the latched clock and transport to match, not just a bypass. Software
        // volume still rescales samples, so it withholds the claim even while the stages are bypassed.
        val routeVerified = if (facts.usbExclusive) bypass && usbRouteVerified else bypass
        return BitPerfectDecision(
            requested = facts.bitPerfectRequested,
            bypass = bypass,
            verifiedBitPerfect = routeVerified && !softwareVolumeActive,
            resamplerActive = resamplerActive,
            softwareVolumeActive = softwareVolumeActive,
            failureReason = failure,
        )
    }
}
