/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain

/**
 * Names the shared domain module in the KMP graph. Playback snapshot and clock types live in the
 * `playback` package. Queue and source policy arrives with the M1 domain milestone.
 */
object SharedDomainModule {
    const val NAME: String = "shared-domain"
}
