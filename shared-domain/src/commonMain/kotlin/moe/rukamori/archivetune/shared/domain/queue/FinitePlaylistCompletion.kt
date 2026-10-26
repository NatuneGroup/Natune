/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

/** Bounds and checks for whole-list completion. Every rule fails closed as an incomplete list. */
object FinitePlaylistCompletion {
    /** Requests one completion may spend, the first page included. */
    const val MAX_REQUESTS = 200

    /** Wall-clock bound the adapter applies to one completion. */
    const val TIMEOUT_MS = 120_000L

    /**
     * Rejects a continuation that is blank or repeats one already consumed, and rejects a page that
     * ends with no continuation and no songs. The last rule matters because an unrecognized
     * continuation envelope parses as empty and null, with no bit that says so.
     */
    fun validateNext(token: String?, consumed: Set<String>, songCount: Int, initial: Boolean = false) {
        if (token != null && (token.isBlank() || token in consumed)) throw IncompleteFinitePlaylistException()
        if (!initial && token == null && songCount == 0) throw IncompleteFinitePlaylistException()
    }

    /**
     * An empty first page counts as an empty playlist only when its header states an explicit zero
     * count. An unknown, localized or missing count stays incomplete rather than false success.
     */
    fun requireConfirmedInitial(page: FinitePlaylistPage<*>) {
        if (page.songs.isNotEmpty() || page.continuation != null) return
        val count = page.songCountText?.let { text -> Regex("""\d+""").findAll(text).map { it.value }.toList() }
        if (count != listOf("0")) throw IncompleteFinitePlaylistException()
    }
}

/**
 * Whole-list collection as a step machine. The adapter loops: ask for the next token, fetch that
 * page, accept it. Nothing is committed until [complete] returns, so a short or failed walk never
 * leaves a partial playlist behind.
 */
class FinitePlaylistCollector<T>(first: FinitePlaylistPage<T>) {
    val title: String? = first.title
    private val consumed = mutableSetOf<String>()
    private val setIds = mutableSetOf<String>()
    private val songs = mutableListOf<FinitePlaylistSong<T>>()
    private var pendingToken: String? = first.continuation
    private var requests = 1

    init {
        FinitePlaylistCompletion.validateNext(first.continuation, emptySet(), first.songs.size, initial = true)
        FinitePlaylistCompletion.requireConfirmedInitial(first)
        songs += keepFirstOccurrences(first.songs)
    }

    /** The continuation to request next, or null once the list is complete. */
    fun nextRequestToken(): String? {
        val token = pendingToken ?: return null
        if (++requests > FinitePlaylistCompletion.MAX_REQUESTS || !consumed.add(token)) {
            throw IncompleteFinitePlaylistException()
        }
        return token
    }

    /** Takes the page answering the token from [nextRequestToken]. */
    fun accept(page: FinitePlaylistPage<T>) {
        check(pendingToken != null) { "No continuation is pending" }
        FinitePlaylistCompletion.validateNext(page.continuation, consumed, page.songs.size)
        songs += keepFirstOccurrences(page.songs)
        pendingToken = page.continuation
    }

    /** The complete list. Throws while a continuation is still pending. */
    fun complete(): FinitePlaylistCompleteList<T> {
        if (pendingToken != null) throw IncompleteFinitePlaylistException()
        return FinitePlaylistCompleteList(title, songs.toList())
    }

    /** A song without a set id is always kept; a repeat of a set id is dropped. */
    private fun keepFirstOccurrences(page: List<FinitePlaylistSong<T>>): List<FinitePlaylistSong<T>> =
        page.filter { song -> song.setVideoId?.takeIf(String::isNotBlank)?.let(setIds::add) ?: true }
}

/** A finite playlist confirmed complete, ready to be committed in one step. */
data class FinitePlaylistCompleteList<T>(
    val title: String?,
    val songs: List<FinitePlaylistSong<T>>,
)
