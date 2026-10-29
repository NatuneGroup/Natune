package moe.rukamori.archivetune.shared.domain.settings

fun resolveAudioOffloadRequest(
    requested: Boolean,
    crossfadeEnabled: Boolean,
    automixEnabled: Boolean,
    authenticityRejectionEnabled: Boolean,
    processingEnabled: Boolean = false,
    usbOutputEnabled: Boolean = false,
): Boolean = requested && !crossfadeEnabled && !automixEnabled && !authenticityRejectionEnabled &&
    !processingEnabled && !usbOutputEnabled

fun audioNormalizationApplies(requested: Boolean, bitPerfectSinkActive: Boolean): Boolean =
    requested && !bitPerfectSinkActive
