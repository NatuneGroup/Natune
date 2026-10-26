package moe.rukamori.archivetune.shared.domain.repository

import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope

data class ContentShelf(val section: ContentSection, val title: String, val page: ContentPage)
data class DiscoveryContent(val shelves: List<ContentShelf>)
interface DiscoveryRepository { suspend fun load(forceRefresh: Boolean = false): DiscoveryContent }

/** Retained NewsItem fields. No endpoint is invented for the currently inactive Android news feed. */
data class ContentNews(val id: String, val title: String, val description: String, val imageUrls: List<String>,
    val important: Boolean, val author: String, val timestamp: Long)
interface NewsRepository {
    suspend fun list(): List<ContentNews>
    suspend fun content(id: String): String
}

enum class HistoryOrigin { LOCAL, YOUTUBE }
data class ListeningEvent(val id: String, val item: ContentOccurrence, val timestampMs: Long, val playTimeMs: Long)
data class ListeningHistoryPage(val events: List<ListeningEvent>, val continuation: String?)
interface HistoryRepository {
    suspend fun page(origin: HistoryOrigin, continuation: String? = null): ListeningHistoryPage
    suspend fun remove(origin: HistoryOrigin, eventIds: Set<String>)
}

/** Epoch-millisecond half-open bounds; the host retains the user's timezone/calendar selection. */
data class ListeningWindow(val fromMs: Long, val untilMs: Long) {
    init { require(untilMs > fromMs) }
}
/**
 * [navigable] is false for an artist or album the provider did not identify. Its [ContentItem.ref] then carries the
 * display name where an id would be, so the UI shows it but must not navigate to it or block it.
 */
data class ListeningRank(
    val item: ContentItem,
    val playCount: Long,
    val playTimeMs: Long,
    val navigable: Boolean = true,
)
data class ListeningSlot(val index: Int, val playCount: Long, val playTimeMs: Long)
data class ListeningStatistics(val songs: List<ListeningRank>, val artists: List<ListeningRank>,
    val albums: List<ListeningRank>, val byHour: List<ListeningSlot>, val byWeekday: List<ListeningSlot>,
    val playCount: Long, val playTimeMs: Long)
interface StatisticsRepository {
    suspend fun load(window: ListeningWindow): ListeningStatistics
    suspend fun reset()
}

interface PodcastActions { suspend fun subscribe(podcast: ContentRef, subscribed: Boolean) }
enum class DownloadPhase { QUEUED, DOWNLOADING, STOPPED, COMPLETED, FAILED, REMOVING }
data class ContentDownload(val id: String, val ref: ContentRef, val phase: DownloadPhase,
    val bytesDownloaded: Long, val totalBytes: Long?)
interface DownloadActions {
    fun observe(): Flow<List<ContentDownload>>
    suspend fun start(ref: ContentRef, quality: String)
    suspend fun stop(id: String)
    suspend fun resume(id: String)
    suspend fun remove(id: String)
}

enum class ContentAccountPhase { SIGNED_OUT, AUTHENTICATING, SIGNED_IN, EXPIRED, UNAVAILABLE }
data class ContentAccount(val provider: ContentProvider, val scope: CredentialScope, val phase: ContentAccountPhase,
    val userId: String?, val displayName: String?, val avatarUri: String?)
interface ContentAccountActions {
    fun observe(provider: ContentProvider, scope: CredentialScope): Flow<ContentAccount>
    suspend fun signIn(provider: ContentProvider, scope: CredentialScope)
    suspend fun signOut(provider: ContentProvider, scope: CredentialScope)
}
/** Social playback/profile operations stay separate from OAuth/account token ownership. */
interface SocialActions {
    suspend fun profile(provider: ContentProvider, userId: String): ContentShelf
    suspend fun share(ref: ContentRef): String
}
