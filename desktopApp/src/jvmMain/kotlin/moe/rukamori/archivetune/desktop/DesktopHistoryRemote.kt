package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.ui.screens.HistoryEventUi
import moe.rukamori.archivetune.shared.ui.screens.HistorySectionUi
import moe.rukamori.archivetune.shared.ui.host.RemoteHistoryPager
import moe.rukamori.archivetune.shared.ui.screens.HistorySource
import moe.rukamori.archivetune.spotify.playedAtMillis

/**
 * The account history feeds beside the local one: the YouTube Music history the signed-in account reports and the
 * Spotify catalog account's recent plays. Both sit on one main-thread pager shape; their loads write only their own
 * fields, so a slow account never blocks the local feed.
 */

private const val COLLECTION_YT_HISTORY = "history:youtube"

/**
 * The YouTube Music account history: the provider's own sections of recently played songs, loaded on first select
 * and re-read on retry. The endpoint answers one response without a continuation, so there is nothing to page.
 */
internal class DesktopYouTubeHistoryPager(
    private val graph: DesktopSessionGraph,
) : RemoteHistoryPager {
    override val source = HistorySource.REMOTE
    override val playable = true
    override val rowMenu = true
    override var sections by mutableStateOf<List<HistorySectionUi>>(emptyList())
    override var query = ""
        set(value) {
            field = value
            rebuild()
        }
    override var loading by mutableStateOf(false)
    override var failed by mutableStateOf(false)
    override var canLoadMore by mutableStateOf(false)
    override var busy by mutableStateOf(false)

    /** The provider's own sections, newest first; rows hold only song entries. */
    private var raw: List<moe.rukamori.archivetune.shared.data.provider.YouTubeHistorySection> = emptyList()
    private var initialized = false

    override fun loadOnce() {
        if (initialized || loading) return
        reload()
    }

    override fun reload() {
        if (loading) return
        loading = true
        failed = false
        graph.scope.launch {
            val read = graph.catalogue.historySections()
            raw = read
            rebuild()
        }.invokeOnCompletion { failure ->
            loading = false
            initialized = true
            if (failure != null && failure !is CancellationException) failed = true
        }
    }

    override fun loadMore() {}

    /** Rebuilds the shown sections from the held ones, honoring the search box's filter like the baseline. */
    private fun rebuild() {
        sections = raw.mapNotNull { section ->
            val rows = section.entries
                .filter { it.kind == ContentKind.SONG }
                .filter {
                    query.isBlank() ||
                        it.title.contains(query, ignoreCase = true) ||
                        it.subtitle.contains(query, ignoreCase = true)
                }
            if (rows.isEmpty()) return@mapNotNull null
            HistorySectionUi(
                key = "yt:${section.title}",
                dateLabel = section.title,
                events = rows.mapIndexed { index, entry ->
                    HistoryEventUi(null, entry.toCatalogueOccurrence(COLLECTION_YT_HISTORY, index), "")
                },
            )
        }
    }

    /**
     * A tapped row starts that song's watch radio, exactly as the baseline plays a remote-history song — the
     * provider owns the continuation, so the loaded prefix is never handed to the queue.
     */
    override fun activate(event: HistoryEventUi, sectionKey: String) {
        val ref = event.row.item.ref
        if (ref.kind != ContentKind.SONG) return
        graph.scope.launch {
            try {
                graph.queue.load(
                    QueueLoadRequest(
                        collection = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.ARTIST, text = ref.id),
                        kind = QueueCollectionKind.RADIO,
                        start = QueueStart(ref, event.row.occurrenceId, event.row.setVideoId),
                        playWhenReady = true,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.report("the song's radio could not be started")
            }
        }
    }

    /** Every loaded row in the search filter's set, shuffled — the baseline's ListQueue shuffle of what it fetched. */
    override fun shuffle(query: String) {
        val rows = sections.flatMap { it.events }.map { it.row }
        if (rows.isEmpty()) return
        val shuffled = rows.shuffled()
        graph.scope.launch {
            try {
                graph.queue.replace(shuffled, shuffled.first().occurrenceId, playWhenReady = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.report("the history shuffle could not be played")
            }
        }
    }
}

/**
 * The Spotify catalog account's recent plays. Rows are Spotify tracks: the desktop has no Spotify playback or
 * track-identification path yet, so the feed is read-only and its rows and shuffle stay refused — never a silent
 * no-op. A tap reports that honestly.
 */
internal class DesktopSpotifyHistoryPager(
    private val graph: DesktopSessionGraph,
    private val accounts: DesktopSpotifyAccounts,
) : RemoteHistoryPager {
    override val source = HistorySource.SPOTIFY
    override val playable = false
    override val rowMenu = false
    override var sections by mutableStateOf<List<HistorySectionUi>>(emptyList())
    override var query = ""
        set(value) {
            field = value
            sections = rawSections.filtered(value)
        }
    override var loading by mutableStateOf(false)
    override var failed by mutableStateOf(false)
    override var canLoadMore by mutableStateOf(false)
    override var busy by mutableStateOf(false)

    private var rawSections: List<HistorySectionUi> = emptyList()
    private var initialized = false

    override fun loadOnce() {
        if (initialized || loading) return
        reload()
    }

    override fun reload() {
        if (loading) return
        loading = true
        failed = false
        graph.scope.launch {
            val plays = accounts.recentlyPlayed(force = initialized)
            rawSections = buildSections(plays)
            sections = rawSections.filtered(query)
        }.invokeOnCompletion { failure ->
            loading = false
            initialized = true
            if (failure != null && failure !is CancellationException) failed = true
        }
    }

    override fun loadMore() {}

    /**
     * Groups the plays into the baseline's day buckets: a UTC stamp is zoned to the device calendar before it is
     * bucketed, and a section's rows are distinct by track. Rows the search box rejects are dropped at display.
     */
    private suspend fun buildSections(
        plays: List<moe.rukamori.archivetune.spotify.models.SpotifyPlayHistory>,
    ): List<HistorySectionUi> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastMonday = thisMonday.minusDays(7)
        // Keys use the host's day vocabulary so the page localizes the fixed buckets; month buckets
        // carry their own formatted label like the local pager's month fallback.
        val groups = LinkedHashMap<String, Triple<String, Int, MutableList<Pair<moe.rukamori.archivetune.spotify.models.SpotifyTrack, Long>>>>()
        for (play in plays) {
            val track = play.track ?: continue
            val playedAtMs = play.playedAtMillis() ?: continue
            val played = Instant.ofEpochMilli(playedAtMs).atZone(zone).toLocalDate()
            val daysAgo = ChronoUnit.DAYS.between(played, today).toInt()
            val (key, label, order) = when {
                daysAgo <= 0 -> Triple("today", "", 0)
                daysAgo == 1 -> Triple("yesterday", "", 1)
                played >= thisMonday -> Triple("this_week", "", 2)
                played >= lastMonday -> Triple("last_week", "", 3)
                else -> {
                    val month = played.withDayOfMonth(1)
                    Triple("month-$month", month.format(MONTH_LABEL), ChronoUnit.DAYS.between(month, today).toInt())
                }
            }
            val bucket = groups.getOrPut(key) { Triple(label, order, ArrayList()) }
            if (bucket.third.none { it.first.id == track.id }) bucket.third += track to playedAtMs
        }
        return groups.entries
            .sortedBy { it.value.second }
            .map { (key, bucket) ->
                val (label, _, tracks) = bucket
                HistorySectionUi(
                    key = key,
                    dateLabel = label,
                    events = tracks.map { (track, playedAtMs) ->
                        HistoryEventUi(null, accounts.historyTrackRow(track, playedAtMs), "")
                    },
                )
            }
            .filter { it.events.isNotEmpty() }
    }

    /** The held sections under the search box's filter; the unfiltered sections stay held for the next query. */
    private fun List<HistorySectionUi>.filtered(query: String): List<HistorySectionUi> {
        if (query.isBlank()) return this
        return mapNotNull { section ->
            val events = section.events.filter {
                it.row.item.title.contains(query, ignoreCase = true) ||
                    it.row.item.subtitle.contains(query, ignoreCase = true)
            }
            if (events.isEmpty()) null else section.copy(events = events)
        }
    }

    override fun activate(event: HistoryEventUi, sectionKey: String) {
        if (busy) return
        busy = true
        graph.scope.launch {
            try {
                accounts.playHistoryTrack(event.row)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("spotify history row not played: ${failure.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    // Android's Spotify history offers no row shuffle; the source stays non-playable for that purpose.
    override fun shuffle(query: String) {}

    private companion object {
        const val SPOTIFY_PROVIDER = "spotify"
        val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM")
    }
}
