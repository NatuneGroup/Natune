/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.source

/**
 * The built-in source priority and the enabled defaults. YouTube stays last as the terminal
 * fallback. Whether a source can resolve is the enabled flags' business, so the order only ranks
 * sources and never switches one on.
 */
object AudioSourceConfig {
    /**
     * Every source in its built-in priority. JioSaavn sits before Amazon, as in the shipped Android
     * order, and Spotify sits just above YouTube.
     */
    val DEFAULT_ORDER: List<AudioSourceType> =
        listOf(
            AudioSourceType.TIDAL,
            AudioSourceType.QOBUZ,
            AudioSourceType.QOBUZ_BACKUP,
            AudioSourceType.DEEZER,
            AudioSourceType.APPLE,
            AudioSourceType.JIOSAAVN,
            AudioSourceType.AMAZON,
            AudioSourceType.QQ,
            AudioSourceType.SPOTIFY,
            AudioSourceType.YOUTUBE,
        )

    /**
     * Tidal and YouTube are on out of the box and everything else is off. Spotify's switch also needs a
     * signed-in streaming account before it counts, which [PlaybackSourceChain.enabledSet] applies.
     */
    val DEFAULT_ENABLED: Map<AudioSourceType, Boolean> =
        AudioSourceType.entries.associateWith { it == AudioSourceType.TIDAL || it == AudioSourceType.YOUTUBE }

    /**
     * The effective order of every source from a stored CSV. The user's order is kept, including where
     * YouTube sits. Sources the CSV does not know yet go directly above YouTube. Unknown names and
     * repeats are dropped. A blank CSV means the built-in order.
     */
    fun parseOrder(rawOrder: String?): List<AudioSourceType> {
        val stored =
            rawOrder
                ?.split(',')
                ?.mapNotNull { parseAudioSourceType(it) }
                ?.distinct()
                .orEmpty()
        if (stored.isEmpty()) return DEFAULT_ORDER

        val missing = DEFAULT_ORDER.filterNot { it in stored }
        if (missing.isEmpty()) return stored

        val merged = mutableListOf<AudioSourceType>()
        var inserted = false
        for (source in stored) {
            if (!inserted && source == AudioSourceType.YOUTUBE) {
                merged.addAll(missing)
                inserted = true
            }
            merged.add(source)
        }
        // With no YouTube in the stored order, the missing block goes last. YouTube is last in
        // DEFAULT_ORDER, so it still ends up as the final fallback.
        if (!inserted) merged.addAll(missing)
        return merged
    }

    /**
     * The one-time migration of a stored order to the current catalogue. Its output is what
     * [parseOrder] would read, joined back into CSV, so the stored value and the resolver agree. A blank
     * value stays blank and keeps tracking [DEFAULT_ORDER]. Running it again changes nothing.
     */
    fun migrateStoredOrder(rawOrder: String?): String {
        if (rawOrder.isNullOrBlank()) return rawOrder ?: ""
        return parseOrder(rawOrder).joinToString(",") { it.name }
    }
}
