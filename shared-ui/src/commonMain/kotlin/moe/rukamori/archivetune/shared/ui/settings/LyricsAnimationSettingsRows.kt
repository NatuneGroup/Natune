package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*

fun lyricsAnimationSettingsRows(): List<SettingsRow> = listOf(
    SettingsRow.Toggle("lyrics_animation_style", Res.string.m6_line_bounce, LyricsV2LrcBounceEnabledKey, true,
        Res.string.m6_line_bounce_description),
    SettingsRow.Toggle("lyrics_adaptive_scroll_spring", Res.string.m6_adaptive_scroll, LyricsV2AdaptiveScrollSpringKey, true,
        Res.string.m6_adaptive_scroll_description),
    SettingsRow.Toggle("lyrics_soft_word_reveal", Res.string.m6_soft_reveal, LyricsV2SoftWordRevealKey, true,
        Res.string.m6_soft_reveal_description),
    SettingsRow.Toggle("lyrics_emphasize_last_word", Res.string.m6_last_word, LyricsV2EmphasizeLastWordKey, false,
        Res.string.m6_last_word_description),
    SettingsRow.Toggle("lyrics_interlude_dots", Res.string.m6_interlude_dots, LyricsV2InterludeDotsKey, true,
        Res.string.m6_interlude_dots_description),
    SettingsRow.Decimal("lyrics_scale_animation", Res.string.m6_bounce_amplitude, LyricsV2BounceFactorKey, 1f, 0f..2f),
    SettingsRow.Decimal("lyrics_glow_animation", Res.string.m6_glow_intensity, LyricsV2GlowFactorKey, 1f, 0f..2f),
    SettingsRow.Decimal("lyrics_fade_animation", Res.string.m6_fill_smoothness, LyricsV2FillTransitionWidthKey, 8f, 2f..24f),
)
