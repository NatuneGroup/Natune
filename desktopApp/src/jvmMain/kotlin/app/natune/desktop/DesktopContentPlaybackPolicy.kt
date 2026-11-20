package app.natune.desktop

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import app.natune.shared.domain.repository.ContentOccurrence
import app.natune.shared.domain.repository.hasBlockedArtist
import app.natune.shared.domain.settings.HideExplicitKey
import app.natune.shared.domain.settings.HideVideoKey

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
