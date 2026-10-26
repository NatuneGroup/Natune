/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.playback

/**
 * A plaintext audio view that a host prepared from a native recipe or a TIDAL manifest. It names the format and the
 * length and nothing more: the bytes stay in the host, so shared-domain carries no transport, lease or decryption type.
 *
 * One handle is one view, so one engine load consumes it. The view keeps its read position and its lease, and a second
 * play of the same occurrence therefore needs a fresh preparation rather than a reused handle.
 */
interface PreparedPlaintextHandle {
    /** The MIME type of the decrypted audio, which the engine uses to configure its decoder. */
    val mimeType: String

    /** The codec of the decrypted audio. */
    val codec: String

    /**
     * The plaintext length in bytes, or null when the lease does not state one. An engine must then treat the stream as
     * unknown-length and must never assume zero.
     */
    val lengthBytes: Long?

    /**
     * The instant the authorization behind the view stops, or null when it has no expiry. A read after that instant
     * fails, so the host prepares the occurrence again before it.
     */
    val expiresAtEpochMs: Long?
}
