/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.unicode

/**
 * Unicode 15.0.0 Grapheme_Cluster_Break property values from GraphemeBreakProperty.txt. Ordinals are the
 * indices stored in [GRAPHEME_CLUSTER_BREAK_RANGE_TABLE]; regenerate both together. [OTHER] is the
 * `@missing` default for unlisted code points.
 */
enum class GraphemeClusterBreak {
    OTHER,
    CR,
    LF,
    CONTROL,
    EXTEND,
    ZWJ,
    REGIONAL_INDICATOR,
    PREPEND,
    SPACING_MARK,
    L,
    V,
    T,
    LV,
    LVT,
}
