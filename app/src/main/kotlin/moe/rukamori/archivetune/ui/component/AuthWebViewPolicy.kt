package moe.rukamori.archivetune.ui.component

internal data class AuthWebViewDarkeningPolicy(
    val disableViewForceDark: Boolean,
    val disableAlgorithmicDarkening: Boolean,
    val disableLegacyForceDark: Boolean,
)

/** WebView capabilities, unlike View's force-dark property, are not determined by the OS version. */
internal fun authWebViewDarkeningPolicy(
    sdkInt: Int,
    algorithmicDarkeningSupported: Boolean,
    legacyForceDarkSupported: Boolean,
): AuthWebViewDarkeningPolicy = AuthWebViewDarkeningPolicy(
    disableViewForceDark = sdkInt >= 29,
    disableAlgorithmicDarkening = algorithmicDarkeningSupported,
    disableLegacyForceDark = legacyForceDarkSupported,
)

/** Ignore initial/empty layout and keyboard dismissal; only a smaller or reflowed viewport needs help. */
internal fun shouldRevealFocusedAuthInput(
    oldWidth: Int,
    oldHeight: Int,
    width: Int,
    height: Int,
): Boolean = oldWidth > 0 && oldHeight > 0 && width > 0 && height > 0 &&
    (height < oldHeight || width != oldWidth)
