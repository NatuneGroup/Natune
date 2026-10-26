/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.library

import moe.rukamori.archivetune.shared.domain.settings.BooleanPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.StringPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.toEnum

/**
 * The sort of one Library list: the order and its direction. The type and the direction are stored under two keys and
 * move together when a sort is chosen.
 */
data class LibrarySortState<T : Enum<T>>(
    val type: T,
    val descending: Boolean,
) {
    /** The direction the list is actually sorted in. A custom order has no direction, so it is never reversed. */
    val effectiveDescending: Boolean get() = descending && allowsSortDirection(type)

    /** Chooses a sort type. The stored direction stays as it was. */
    fun selectType(next: T): LibrarySortState<T> = copy(type = next)

    /** Flips the direction where the sort has one. A custom order is returned unchanged. */
    fun toggleDescending(): LibrarySortState<T> = if (allowsSortDirection(type)) copy(descending = !descending) else this
}

/** Playlist orders keep a custom sequence, which is the playlist's own order and has no direction to flip. */
fun allowsSortDirection(type: Enum<*>): Boolean =
    type != PlaylistSongSortType.CUSTOM && type != PlaylistSortType.CUSTOM

/**
 * Reads one Library sort. A stored type that no longer exists falls back to the default type, and the stored direction
 * still applies. A missing direction reads as the default direction.
 */
inline fun <reified T : Enum<T>> PreferenceSnapshot.librarySortState(
    typeKey: StringPreferenceKey,
    descendingKey: BooleanPreferenceKey,
    default: LibrarySortState<T>,
): LibrarySortState<T> =
    LibrarySortState(
        type = this[typeKey].toEnum(default.type),
        descending = this[descendingKey] ?: default.descending,
    )

/** Writes the type and the direction together, so a single edit never leaves one of them behind. */
fun <T : Enum<T>> LibrarySortState<T>.writeTo(
    editor: PreferenceEditor,
    typeKey: StringPreferenceKey,
    descendingKey: BooleanPreferenceKey,
) {
    editor[typeKey] = type.name
    editor[descendingKey] = descending
}
