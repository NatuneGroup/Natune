/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.platform

/**
 * Machine-readable host diagnostics, not translated UI labels or an assertion of runtime parity.
 * Source presence never upgrades Apple SDK compilation or execution to verified.
 */
object IosCapabilities {
    const val verification: String = "unverified-on-linux-host"
    val adapters: Map<String, String> = mapOf(
        "audio" to "portable-core-avAudioSourceNode-requires-cinterop-and-native-link",
        "audio-session" to "playback-background-interruption-route-change",
        "now-playing" to "snapshot-driven-remote-command-dispatch-requires-queue-composition",
        "credentials" to "keychain-device-only-no-plaintext-fallback",
        "browser-auth" to "ephemeral-asWebAuthenticationSession",
        "embedded-auth" to "isolated-wkWebView",
        "files" to "document-picker-security-scoped-bookmarks",
        "japanese-tokenizer" to "common-kuromoji-pack-lifecycle-requires-host-settings",
    )
    val unavailable: Set<String> = setOf(
        "carplay-entitlement",
        "android-usb-direct",
        "bit-perfect-output-proof",
        "protected-media-pcm",
    )
}
