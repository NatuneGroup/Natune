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
     * The persisted [providerName] as the attribution label for "Lyrics from …". Provider keys
     * are already the canonical brand labels the settings rows use — "lrc.red", "LrcLib" — so the
     * key is shown as stored; only a stored URL collapses to its host so the chip never shows a
     * link.
     */
    val displayProviderName: String
        get() = providerDisplayName(providerName)

    companion object {
        const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND"

        fun providerDisplayName(providerName: String): String {
            val raw = providerName.trim()
            return raw.substringAfter("://", raw).substringBefore('/').removePrefix("www.").ifBlank { raw }
        }
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
