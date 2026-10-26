/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.ai

/**
 * The local request-budget family a non-lyric or lyric AI call is charged against. Each family has its
 * own spacing and hourly window in the shared transport limiter, so one feature cannot spend another's
 * budget and a failed call is refunded only in its own family.
 */
enum class AiRequestBudget {
    LYRICS_TRANSLATION,
    LYRICS_ROMANIZATION,
    AI_MIX,
}
