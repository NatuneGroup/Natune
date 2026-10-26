/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.ai

/**
 * A provider model offered by the settings picker. [displayName] falls back to [id] when the provider
 * sends no friendly name, so the picker always has a label to show.
 */
data class AiModelOption(
    val id: String,
    val displayName: String,
)
