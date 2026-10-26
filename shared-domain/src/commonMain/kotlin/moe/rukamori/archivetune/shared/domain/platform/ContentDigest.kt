/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.platform

import moe.rukamori.archivetune.shared.domain.lyrics.ai.Sha256

/** Reuse the existing portable implementation instead of introducing a second digest engine. */
object ContentDigest {
    fun sha256(bytes: ByteArray): String = Sha256.hexDigest(bytes)
}
