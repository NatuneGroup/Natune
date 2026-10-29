/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.HistorySource
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.EventWithSong
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.pages.HistoryPage
import moe.rukamori.archivetune.kmp.data.AndroidYouTubeHistory
import moe.rukamori.archivetune.shared.data.provider.HistoryPageAccumulator
import moe.rukamori.archivetune.shared.data.provider.HistoryBrowsePage
import moe.rukamori.archivetune.shared.data.provider.YouTubeHistoryRoutingUnavailable
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountChanged
import moe.rukamori.archivetune.utils.reportException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        val database: MusicDatabase,
    ) : ViewModel() {
        var historySource = MutableStateFlow(HistorySource.LOCAL)
        private val _remoteHistoryState = MutableStateFlow<RemoteHistoryUiState>(RemoteHistoryUiState.Loading)
        val remoteHistoryState: StateFlow<RemoteHistoryUiState> = _remoteHistoryState
        private val loadedEvents = mutableListOf<EventWithSong>()
        val events = MutableStateFlow<Map<DateAgo, List<EventWithSong>>>(emptyMap())
        val isLoadingMoreEvents = MutableStateFlow(false)
        val canLoadMoreEvents = MutableStateFlow(true)
        val remoteContinuation = MutableStateFlow<String?>(null)
        val remoteLoading = MutableStateFlow(false)
        val remoteLoadFailed = MutableStateFlow(false)
        val remotePagingUnavailable = MutableStateFlow(false)
        val remoteShuffling = MutableStateFlow(false)
        private val remotePages = HistoryPageAccumulator<SongItem> { it.id }
        private var remoteJob: Job? = null
        private var remoteShuffleJob: Job? = null
        private var remoteShuffleGeneration = 0L
        private var remoteGeneration = 0L
        private var remoteAccount = currentRemoteAccount()

        private val today = LocalDate.now()
        private val thisMonday = today.with(DayOfWeek.MONDAY)
        private val lastMonday = thisMonday.minusDays(7)

        init {
            loadMoreEvents()
            viewModelScope.launch {
                YouTube.authStateFlow.map { it.cookie to it.dataSyncId }.distinctUntilChanged().collect {
                    if (it != remoteAccount) resetRemoteAccount(it)
                }
            }
        }

        private fun currentRemoteAccount() = YouTube.currentPlaybackAuthState().let { it.cookie to it.dataSyncId }

        fun remoteAccountIsCurrent(cookie: String?, dataSyncId: String?): Boolean {
            val expected = cookie to dataSyncId
            return remoteAccount == expected && currentRemoteAccount() == expected
        }

        private fun resetRemoteAccount(account: Pair<String?, String?>) {
            remoteShuffleJob?.cancel()
            remoteShuffleGeneration++
            remoteShuffling.value = false
            remoteJob?.cancel()
            remoteGeneration++
            remoteAccount = account
            remotePages.replace(HistoryBrowsePage(emptyList()))
            remoteContinuation.value = null
            remoteLoading.value = false
            remoteLoadFailed.value = false
            remotePagingUnavailable.value = false
            _remoteHistoryState.value = RemoteHistoryUiState.Loading
        }

        fun loadMoreEvents() {
            if (isLoadingMoreEvents.value || !canLoadMoreEvents.value) return

            isLoadingMoreEvents.value = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val page = database.events(limit = HISTORY_PAGE_SIZE, offset = loadedEvents.size)
                    loadedEvents += page
                    events.value = loadedEvents.groupEventsByDate()
                    canLoadMoreEvents.value = page.size == HISTORY_PAGE_SIZE
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    reportException(exception)
                    canLoadMoreEvents.value = false
                } finally {
                    isLoadingMoreEvents.value = false
                }
            }
        }

        fun fetchRemoteHistory() {
            if (remoteShuffling.value) return
            requestRemote(reset = true)
        }

        fun loadMoreRemoteHistory() {
            if (remoteShuffling.value) return
            if (remoteContinuation.value != null && !remoteLoadFailed.value) requestRemote(reset = false)
        }

        fun retryRemoteHistory() {
            if (remoteShuffling.value) return
            requestRemote(reset = remotePagingUnavailable.value || _remoteHistoryState.value !is RemoteHistoryUiState.Success)
        }

        /** A silent refresh keeps cached sections visible on failure. All pager mutations run on Main. */
        suspend fun fetchRemoteHistorySilent() = withContext(Dispatchers.Main.immediate) {
            if (remoteShuffling.value) return@withContext
            requestRemote(reset = true, silent = true)?.join()
        }

        /** Refuse an unknown/failed tail instead of calling a loaded prefix "shuffle all". */
        fun shuffleRemoteHistory(query: String, play: (List<SongItem>) -> Unit) {
            if (remoteLoading.value || remoteShuffling.value || remoteLoadFailed.value) return
            val account = currentRemoteAccount()
            if (account != remoteAccount) {
                resetRemoteAccount(account)
                return
            }
            val shuffleGeneration = ++remoteShuffleGeneration
            remoteShuffling.value = true
            remoteShuffleJob = viewModelScope.launch {
                try {
                    if (remotePagingUnavailable.value) throw YouTubeHistoryRoutingUnavailable()
                    var count = 0
                    while (remoteContinuation.value != null && count++ < 100) {
                        val request = requestRemote(reset = false) ?: error("History page could not start")
                        request.join()
                        ensureActive()
                        if (request.isCancelled) throw CancellationException("History paging was superseded")
                        if (remoteLoadFailed.value) return@launch
                    }
                    check(remoteContinuation.value == null) { "History shuffle page bound reached" }
                    if (currentRemoteAccount() != account) throw ContentAccountChanged()
                    val rows = remotePages.page.sections.flatMap { it.entries }.filter { item ->
                        query.isBlank() || item.title.contains(query, ignoreCase = true) ||
                            item.artists.any { it.name.contains(query, ignoreCase = true) }
                    }
                    if (rows.isNotEmpty()) play(rows.shuffled())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    remoteLoadFailed.value = true
                    if (failure is YouTubeHistoryRoutingUnavailable) remotePagingUnavailable.value = true
                    reportException(failure)
                } finally {
                    if (shuffleGeneration == remoteShuffleGeneration) remoteShuffling.value = false
                }
            }
        }

        private fun requestRemote(reset: Boolean, silent: Boolean = false): Job? {
            val account = currentRemoteAccount()
            if (account != remoteAccount) resetRemoteAccount(account)
            if (!reset && (remoteLoading.value || remoteContinuation.value == null)) return null
            remoteJob?.cancel()
            val generation = ++remoteGeneration
            val before = _remoteHistoryState.value
            if (reset && !silent) _remoteHistoryState.value = RemoteHistoryUiState.Loading
            remoteLoading.value = true
            remoteLoadFailed.value = false
            return viewModelScope.launch {
                try {
                    val token = if (reset) null else remoteContinuation.value
                    val next = withContext(Dispatchers.IO) { AndroidYouTubeHistory.page(token) }
                    ensureActive()
                    if (currentRemoteAccount() != account) throw ContentAccountChanged()
                    if (generation != remoteGeneration) return@launch
                    if (reset) remotePages.replace(next) else remotePages.append(requireNotNull(token), next)
                    remoteContinuation.value = remotePages.page.continuation
                    remotePagingUnavailable.value = remotePages.page.paginationUnavailable
                    val sections = remotePages.page.sections.map { HistoryPage.HistorySection(it.title, it.entries) }
                    _remoteHistoryState.value = if (sections.isNotEmpty() || remoteContinuation.value != null) {
                        RemoteHistoryUiState.Success(HistoryPage(sections))
                    } else RemoteHistoryUiState.Empty
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == remoteGeneration) {
                        remoteLoadFailed.value = true
                        if (e is YouTubeHistoryRoutingUnavailable) remotePagingUnavailable.value = true
                        if (before !is RemoteHistoryUiState.Success || (reset && !silent)) {
                            _remoteHistoryState.value = RemoteHistoryUiState.Error
                        }
                    }
                    reportException(e)
                } finally {
                    if (generation == remoteGeneration) remoteLoading.value = false
                }
            }.also { remoteJob = it }
        }

        /**
         * Non-suspend wrapper for call sites that are not already in a coroutine
         * (e.g. click handlers in Compose).
         */
        fun enqueueSilentFetch() {
            viewModelScope.launch(Dispatchers.IO) {
                fetchRemoteHistorySilent()
            }
        }

        fun removeEventsFromHistory(eventIds: List<Long>) {
            val uniqueEventIds = eventIds.distinct()
            if (uniqueEventIds.isEmpty()) return

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    database.deleteEventsByIds(uniqueEventIds)
                    loadedEvents.clear()
                    events.value = emptyMap()
                    canLoadMoreEvents.value = true
                    loadMoreEvents()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    reportException(exception)
                }
            }
        }

        private fun List<EventWithSong>.groupEventsByDate(): Map<DateAgo, List<EventWithSong>> =
            groupBy {
                val date = it.event.timestamp.toLocalDate()
                val daysAgo = ChronoUnit.DAYS.between(date, today).toInt()
                when {
                    daysAgo == 0 -> DateAgo.Today
                    daysAgo == 1 -> DateAgo.Yesterday
                    date >= thisMonday -> DateAgo.ThisWeek
                    date >= lastMonday -> DateAgo.LastWeek
                    else -> DateAgo.Other(date.withDayOfMonth(1))
                }
            }.toSortedMap(
                compareBy { dateAgo ->
                    when (dateAgo) {
                        DateAgo.Today -> 0L
                        DateAgo.Yesterday -> 1L
                        DateAgo.ThisWeek -> 2L
                        DateAgo.LastWeek -> 3L
                        is DateAgo.Other -> ChronoUnit.DAYS.between(dateAgo.date, today)
                    }
                },
            ).mapValues { entry ->
                entry.value.distinctBy { it.song.id }
            }
    }

private const val HISTORY_PAGE_SIZE = 100

sealed interface RemoteHistoryUiState {
    data object Loading : RemoteHistoryUiState

    data class Success(
        val page: HistoryPage,
    ) : RemoteHistoryUiState

    data object Empty : RemoteHistoryUiState

    data object Error : RemoteHistoryUiState
}

sealed class DateAgo {
    data object Today : DateAgo()

    data object Yesterday : DateAgo()

    data object ThisWeek : DateAgo()

    data object LastWeek : DateAgo()

    class Other(
        val date: LocalDate,
    ) : DateAgo() {
        override fun equals(other: Any?): Boolean {
            if (other is Other) return date == other.date
            return super.equals(other)
        }

        override fun hashCode(): Int = date.hashCode()
    }
}
