package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/** Source picking can be hosted without inventing owners for unrelated menu actions. */
interface PlayerSongSourceState {
    val sourceChoices: List<AudioSourceType>
    val selectedSource: AudioSourceType?
    val amazonSearchEnabled: Boolean
    val searchableSources: Set<AudioSourceType>
}

data class SongSourcePickerState(
    override val sourceChoices: List<AudioSourceType>,
    override val selectedSource: AudioSourceType?,
    override val amazonSearchEnabled: Boolean = false,
    override val searchableSources: Set<AudioSourceType> = emptySet(),
) : PlayerSongSourceState

interface PlayerSongSourceActions {
    fun refreshSongSources(track: PlayerQueueTrack)
    suspend fun searchSongSources(query: String, sourceFilter: AudioSourceType?, includeAmazon: Boolean): List<PlayerSourceSearchResult>
    fun chooseSongSource(track: PlayerQueueTrack, source: AudioSourceType?)
    fun chooseSongSourceSearchResult(track: PlayerQueueTrack, request: PlayerSourceOverrideRequest)
    fun playYouTubeSearchResult(track: PlayerQueueTrack, result: PlayerSourceSearchResult)
}
