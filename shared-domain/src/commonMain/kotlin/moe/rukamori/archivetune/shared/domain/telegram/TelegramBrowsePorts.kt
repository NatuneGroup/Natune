/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.telegram

/** A public channel or supergroup found by search. [memberCount] is the raw count; the host formats it. */
data class TelegramChannelInfo(
    val chatId: Long,
    val title: String,
    val username: String?,
    val memberCount: Int,
    val isBroadcast: Boolean,
    val photoMinithumbnail: ByteArray?,
    val photoFileId: Int,
)

/** Searches public chats through the signed-in Telegram client, keeping only channels and supergroups. */
interface TelegramChannelSearch {
    suspend fun searchChannels(query: String): List<TelegramChannelInfo>
}

/**
 * Turns a channel into a local playlist and starts filling it. The playlist id is `LPtg<chatId>`, and creating it
 * again keeps the existing songs. [startSync] returns without waiting for the sync to finish.
 */
interface TelegramChannelLibrary {
    suspend fun ensurePlaylist(chatId: Long, title: String): String

    fun startSync(chatId: Long, title: String, losslessOnly: Boolean)
}

/** Why a browse action failed. */
sealed interface TelegramBrowseIssue {
    /** [detail] is the client's message, or "?" when it has none. */
    data class SearchFailed(val detail: String) : TelegramBrowseIssue

    data object OpenFailed : TelegramBrowseIssue
}

/** The channel browse state. [searched] stays true after a search, so an empty result reads as a real answer. */
data class TelegramBrowseState(
    val query: String,
    val searching: Boolean,
    val searched: Boolean,
    val opening: Boolean,
    val results: List<TelegramChannelInfo>,
    val issue: TelegramBrowseIssue?,
)
