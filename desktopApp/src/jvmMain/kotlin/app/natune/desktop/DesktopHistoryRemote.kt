package app.natune.desktop

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
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import app.natune.shared.domain.settings.DataSyncIdKey
import app.natune.shared.domain.settings.InnerTubeCookieKey
import app.natune.shared.domain.repository.ContentKind
import app.natune.shared.domain.repository.ContentProvider
import app.natune.shared.domain.repository.ContentRequest
import app.natune.shared.domain.repository.ContentSurface
import app.natune.shared.domain.repository.QueueCollectionKind
import app.natune.shared.domain.repository.QueueLoadRequest
import app.natune.shared.domain.repository.QueueStart
import app.natune.shared.ui.screens.HistoryEventUi
import app.natune.shared.ui.screens.HistorySectionUi
import app.natune.shared.ui.host.RemoteHistoryPager
import app.natune.shared.ui.host.YouTubeHistoryPager
import app.natune.shared.ui.screens.HistorySource
import app.natune.spotify.playedAtMillis

/**
 * The account history feeds beside the local one: the YouTube Music history the signed-in account reports and the
 * Spotify catalog account's recent plays. Both sit on one main-thread pager shape; their loads write only their own
 * fields, so a slow account never blocks the local feed.
 */

/** Dated account history, paged by the same owner as iOS. Song taps retain the baseline's watch radio. */
internal class DesktopYouTubeHistoryPager(
    graph: DesktopSessionGraph,
    isCurrentAccount: () -> Boolean = historyAccountGuard(graph),
) : RemoteHistoryPager by YouTubeHistoryPager(
    scope = graph.scope,
    fetch = { graph.catalogue.historySections(it) },
    play = { row ->
        graph.scope.launch {
            try {
                if (!isCurrentAccount()) return@launch
                val ref = row.item.ref
                if (ref.kind == ContentKind.EPISODE) {
                    graph.queue.replace(listOf(row), row.occurrenceId, playWhenReady = true)
                } else {
                    graph.queue.load(QueueLoadRequest(
                        ContentRequest(ContentProvider.YOUTUBE, ContentSurface.ARTIST, text = ref.id),
                        QueueCollectionKind.RADIO, QueueStart(ref, row.occurrenceId, row.setVideoId), playWhenReady = true,
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.report("the history item could not be played")
            }
        }
    },
    playShuffled = { rows ->
        graph.scope.launch {
            try {
                if (!isCurrentAccount()) return@launch
                graph.queue.replace(rows, rows.first().occurrenceId, playWhenReady = true, staysFinite = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.report("the history shuffle could not be played")
            }
        }
    },
    report = graph::report,
    isCurrentAccount = isCurrentAccount,
    // The provider rows stay canonical; this only layers the signed-in host's song edits onto what is shown.
    overrides = graph.library.songMetadata?.observeOverrides(),
)

private fun historyAccountGuard(graph: DesktopSessionGraph): () -> Boolean {
    fun stored() = PlaybackAuthState(
        cookie = graph.settings.snapshots.value?.get(InnerTubeCookieKey),
        dataSyncId = graph.settings.snapshots.value?.get(DataSyncIdKey),
    ).normalized()
    val expected = stored()
    return {
        val current = stored()
        val applied = YouTube.currentPlaybackAuthState()
        expected.hasLoginCookie && current.cookie == expected.cookie && current.dataSyncId == expected.dataSyncId &&
            applied.cookie == expected.cookie && applied.dataSyncId == expected.dataSyncId
    }
}

/**
 * The Spotify catalog account's recent plays. Rows are Spotify tracks that resolve to their YouTube match through
 * the same anonymous-then-account identification path as Android. The source has no list shuffle, so the page
 * keeps that action hidden while a tap plays the resolved radio.
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
        plays: List<app.natune.spotify.models.SpotifyPlayHistory>,
    ): List<HistorySectionUi> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastMonday = thisMonday.minusDays(7)
        // Keys use the host's day vocabulary so the page localizes the fixed buckets; month buckets
        // carry their own formatted label like the local pager's month fallback.
        val groups = LinkedHashMap<String, Triple<String, Int, MutableList<Pair<app.natune.spotify.models.SpotifyTrack, Long>>>>()
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

    // Android's Spotify history offers no row shuffle, even though a row can resolve and play.
    override fun shuffle(query: String) {}

    private companion object {
        val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM")
    }
}
