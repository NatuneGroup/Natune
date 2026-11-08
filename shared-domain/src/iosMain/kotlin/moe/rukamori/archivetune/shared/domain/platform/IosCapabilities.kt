package moe.rukamori.archivetune.shared.domain.platform

/**
 * Machine-readable host diagnostics, not translated UI labels or an assertion of runtime parity.
 * Source presence never upgrades Apple SDK compilation or execution to verified.
 */
object IosCapabilities {
    const val verification: String = "unverified-on-linux-host"
    val adapters: Map<String, String> = mapOf(
        "carplay" to "cpTemplate-scene-shared-queue-requires-approved-entitlement-and-device-verification",
        "audio" to "portable-core-avAudioSourceNode-requires-cinterop-and-native-link",
        "audio-session" to "playback-background-interruption-route-change",
        "now-playing" to "snapshot-driven-remote-command-dispatch-requires-queue-composition",
        "carplay" to "library-queue-scene-requires-approved-audio-entitlement-and-device-validation",
        "widgets" to "widgetkit-foreground-queue-controls-requires-extension-app-link",
        "dsp" to "avAudioEngine-pitch-eq-preamp-clarity-contour-highpass-partial",
        "credentials" to "keychain-device-only-no-plaintext-fallback",
        "browser-auth" to "ephemeral-asWebAuthenticationSession",
        "embedded-auth" to "isolated-wkWebView",
        "files" to "document-picker-security-scoped-bookmarks",
        "japanese-tokenizer" to "common-kuromoji-pack-lifecycle-requires-host-settings",
        "listener-presence" to "backup-excluded-opt-in-file-ephemeral-nsurlsession-switch-requires-host-settings",
    )
    val unavailable: Set<String> = setOf(
        "carplay-entitlement",
        "android-usb-direct",
        "bit-perfect-output-proof",
        "protected-media-pcm",
    )
}
