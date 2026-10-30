package moe.rukamori.archivetune.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSourceQuality
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSourceSearchResult

/** Searches are bounded independently: one missing account/service must not hide another provider's rows. */
internal class DesktopSourceSearch(
    private val backends: Map<AudioSourceType, suspend (String) -> List<ContentItem>>,
) {
    val searchableSources: Set<AudioSourceType> get() = backends.keys + AudioSourceType.YOUTUBE

    suspend fun search(
        query: String,
        sourceFilter: AudioSourceType?,
        includeAmazon: Boolean,
        youtube: suspend (String) -> List<ContentItem>,
    ): List<PlayerSourceSearchResult> = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.length < 2 || sourceFilter != null && sourceFilter !in searchableSources) {
            return@coroutineScope emptyList()
        }
        val searches = backends + (AudioSourceType.YOUTUBE to youtube)
        SEARCH_ORDER.filter { source ->
            source in searches && (sourceFilter == null || sourceFilter == source) &&
                (source != AudioSourceType.AMAZON || includeAmazon)
        }.map { source ->
            async {
                val rows = try {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) { searches.getValue(source)(trimmed) }.orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                rows.asSequence().filter { it.ref.kind == ContentKind.SONG && it.title.isNotBlank() }
                    .distinctBy { it.ref.id }.take(RESULTS_PER_SOURCE).map { item ->
                        PlayerSourceSearchResult(source, item.ref.id, item.title,
                            item.artists.joinToString(", ") { it.name }.ifBlank { item.subtitle },
                            item.album, item.artworkUri, item.durationMs, PlayerSourceQuality.NONE,
                            isYouTubeSong = source == AudioSourceType.YOUTUBE)
                    }.toList()
            }
        }.awaitAll().flatten()
    }

    companion object {
        // These are the Android chooser's search backends/order, not the separate playback priority.
        val SEARCH_ORDER = listOf(AudioSourceType.YOUTUBE, AudioSourceType.TIDAL, AudioSourceType.AMAZON,
            AudioSourceType.QOBUZ, AudioSourceType.QOBUZ_BACKUP, AudioSourceType.DEEZER, AudioSourceType.JIOSAAVN)
        const val SEARCH_TIMEOUT_MS = 15_000L
        const val RESULTS_PER_SOURCE = 8
    }
}
