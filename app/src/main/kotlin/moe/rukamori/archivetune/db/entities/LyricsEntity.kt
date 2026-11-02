/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val id: String,
    val lyrics: String,
    @ColumnInfo(defaultValue = "'REMOTE'")
    val source: String = Source.REMOTE.value,
    @ColumnInfo(defaultValue = "''")
    val providerName: String = "",
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun hasGenericSource(): Boolean =
        source == Source.REMOTE.value ||
            source == Source.USER_SELECTION.value ||
            source == Source.EMBEDDED.value

    /**
     * The persisted [providerName] as a user-facing source label for the "Lyrics from …"
     * attribution. The stored value is a provider key, not a display name — "lrc.red" is the
     * known offender — so keys that read like hostnames map to proper names, and an unknown
     * value falls back to the key itself, or its host portion when a URL was stored.
     */
    val displayProviderName: String
        get() = providerDisplayName(providerName)

    companion object {
        const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND"

        fun providerDisplayName(providerName: String): String {
            val raw = providerName.trim()
            val key = raw.substringAfter("://", raw).substringBefore('/').removePrefix("www.")
            return PROVIDER_DISPLAY_NAMES[key.lowercase()] ?: key.ifBlank { raw }
        }

        private val PROVIDER_DISPLAY_NAMES =
            mapOf(
                "lrc.red" to "LrcRed",
            )
    }

    enum class Source(
        val value: String,
    ) {
        REMOTE("REMOTE"),
        EMBEDDED("EMBEDDED"),
        USER_SELECTION("USER_SELECTION"),
        USER_EDIT("USER_EDIT"),
        TRANSLATION("TRANSLATION"),
        AI_TRANSLATION("AI_TRANSLATION"),
    }
}
