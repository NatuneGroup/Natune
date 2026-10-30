package moe.rukamori.archivetune.desktop

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.hasBlockedArtist
import moe.rukamori.archivetune.shared.domain.settings.HideExplicitKey
import moe.rukamori.archivetune.shared.domain.settings.HideVideoKey

/** Direct whole-list replacements must obey the same preferences as the portable collection loader. */
internal suspend fun desktopPlayableRows(
    graph: DesktopSessionGraph,
    rows: List<ContentOccurrence>,
): List<ContentOccurrence> {
    val preferences = graph.settings.snapshots.filterNotNull().first()
    val blocked = graph.artistBlocks.observeBlockedArtists().first()
    return rows.filter { row ->
        !(preferences[HideExplicitKey] == true && row.item.explicit) &&
            !(preferences[HideVideoKey] == true && row.item.isVideo) &&
            !row.item.hasBlockedArtist(blocked)
    }
}
