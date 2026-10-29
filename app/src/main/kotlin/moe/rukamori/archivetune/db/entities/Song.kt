/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

@Immutable
data class Song
    @JvmOverloads
    constructor(
        @Embedded val song: SongEntity,
        @Relation(
            entity = ArtistEntity::class,
            entityColumn = "id",
            parentColumn = "id",
            associateBy =
                Junction(
                    value = SortedSongArtistMap::class,
                    parentColumn = "songId",
                    entityColumn = "artistId",
                ),
        )
        val artists: List<ArtistEntity>,
        @Relation(
            entity = AlbumEntity::class,
            entityColumn = "id",
            parentColumn = "id",
            associateBy =
                Junction(
                    value = SongAlbumMap::class,
                    parentColumn = "songId",
                    entityColumn = "albumId",
                ),
        )
        val album: AlbumEntity? = null,
        @Relation(
            parentColumn = "id",
            entityColumn = "id",
        )
        val format: FormatEntity? = null,
        @Relation(
            parentColumn = "id",
            entityColumn = "songId",
        )
        val metadataOverride: SongMetadataOverrideEntity? = null,
    ) : LocalItem() {
        override val id: String
            get() = song.id

        // Stays canonical: edits and provider refresh read it. Display code uses effectiveTitle.
        override val title: String
            get() = song.title
        override val thumbnailUrl: String?
            get() = song.thumbnailUrl

        /** The title the user sees: the edit when there is one, otherwise the canonical title. */
        val effectiveTitle: String
            get() = metadataOverride?.title ?: song.title

        /** The artist names the user sees; an edit replaces the canonical artist list as a whole. */
        val effectiveArtistNames: List<String>
            get() = metadataOverride?.artistNameList() ?: artists.map { it.name }

        /**
         * The copy that presence and listen reports name the song with: the edited title and artist
         * names, with canonical artist rows kept where a name did not change. Resolution keeps the
         * canonical fields, so this is only applied to what is reported.
         */
        fun withEffectiveNames(): Song {
            val edit = metadataOverride ?: return this
            val overriddenNames = edit.artistNameList()
            return copy(
                song = song.copy(title = effectiveTitle),
                artists =
                    overriddenNames?.map { name ->
                        artists.firstOrNull { it.name == name }
                            ?: ArtistEntity(id = "LA_unknown_$name", name = name, isLocal = true)
                    } ?: artists,
            )
        }
    }
