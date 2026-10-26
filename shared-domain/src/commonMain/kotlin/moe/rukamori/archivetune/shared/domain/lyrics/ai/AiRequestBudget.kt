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
