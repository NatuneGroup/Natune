package moe.rukamori.archivetune.shared.data.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class HistoryBrowseSection<T>(val title: String, val entries: List<T>)
data class HistoryBrowsePage<T>(
    val sections: List<HistoryBrowseSection<T>>,
    val continuation: String? = null,
    /** The original first page remains readable, but the selected route has no public continuation API. */
    val paginationUnavailable: Boolean = false,
)
typealias YouTubeHistorySection = HistoryBrowseSection<YouTubeEntry>
typealias YouTubeHistoryPage = HistoryBrowsePage<YouTubeEntry>

class YouTubeHistoryRoutingUnavailable : IllegalStateException("History paging cannot access the configured rotating proxy")

fun YouTubeHistoryPage.entriesPage(): YouTubeEntryPage {
    if (paginationUnavailable) throw YouTubeHistoryRoutingUnavailable()
    return YouTubeEntryPage(sections.flatMap { it.entries }, continuation)
}

/**
 * Reads only history list containers, never menu/watch endpoints (which can carry unrelated continuations).
 * Row decoding stays with the platform's existing InnerTube models. Both dated shelves and bare continuation
 * rows occur in history; a bare page's empty title means "continue the preceding date".
 */
fun <T> parseYouTubeHistoryPage(
    response: JsonObject,
    decodeRow: (JsonObject) -> T?,
): HistoryBrowsePage<T> {
    val sections = ArrayList<HistoryBrowseSection<T>>()
    var continuation: String? = null
    var recognized = false

    fun token(container: JsonObject): String? {
        val next = (container["continuations"] as? JsonArray)?.firstNotNullOfOrNull { value ->
            val data = value as? JsonObject ?: return@firstNotNullOfOrNull null
            (data.obj("nextContinuationData") ?: data.obj("reloadContinuationData"))?.string("continuation")
        }
        return next ?: container.obj("continuationEndpoint")?.obj("continuationCommand")?.string("token")
    }

    fun title(container: JsonObject): String {
        val text = container.obj("title") ?: container.obj("header")?.obj("itemSectionHeaderRenderer")?.obj("title")
        return text?.string("simpleText") ?: (text?.get("runs") as? JsonArray).orEmpty()
            .joinToString("") { (it as? JsonObject)?.string("text").orEmpty() }
    }

    fun visit(container: JsonObject, inheritedTitle: String = "") {
        val label = title(container).ifBlank { inheritedTitle }
        val rows = ArrayList<T>()
        for (value in (container["contents"] as? JsonArray).orEmpty()) {
            val cell = value as? JsonObject ?: continue
            val nested = listOf("musicShelfRenderer", "musicPlaylistShelfRenderer", "itemSectionRenderer", "sectionListRenderer")
                .firstNotNullOfOrNull { cell.obj(it) }
            if (nested != null) {
                if (rows.isNotEmpty()) {
                    sections += HistoryBrowseSection(label, rows.toList())
                    rows.clear()
                }
                visit(nested, label)
            } else {
                if (cell["musicResponsiveListItemRenderer"] != null || cell["musicMultiRowListItemRenderer"] != null) {
                    decodeRow(cell)?.let(rows::add)
                }
                cell.obj("continuationItemRenderer")?.let { token(it)?.let { next -> continuation = next } }
            }
        }
        if (rows.isNotEmpty()) sections += HistoryBrowseSection(label, rows.toList())
        // The list's cursor takes precedence over any shelf-level cursor.
        token(container)?.let { continuation = it }
    }

    val contents = response.obj("contents")
    val tabs = contents?.obj("singleColumnBrowseResultsRenderer") ?: contents?.obj("twoColumnBrowseResultsRenderer")
    val tabList = (tabs?.get("tabs") as? JsonArray).orEmpty()
    val tabSection = tabList.firstNotNullOfOrNull {
        (it as? JsonObject)?.obj("tabRenderer")?.obj("content")?.obj("sectionListRenderer")
    }
    val first = tabSection ?: contents?.obj("sectionListRenderer")
        ?: tabs?.obj("secondaryContents")?.obj("sectionListRenderer")
    if (first != null) {
        recognized = true
        visit(first)
    }
    response.obj("continuationContents")?.let { next ->
        listOf("sectionListContinuation", "itemSectionContinuation", "musicShelfContinuation", "musicPlaylistShelfContinuation")
            .firstNotNullOfOrNull { next.obj(it) }?.let {
                recognized = true
                visit(it)
            }
    }
    for (name in listOf("onResponseReceivedActions", "onResponseReceivedEndpoints")) {
        for (value in (response[name] as? JsonArray).orEmpty()) {
            val action = value as? JsonObject ?: continue
            val append = action.obj("appendContinuationItemsAction") ?: action.obj("reloadContinuationItemsCommand") ?: continue
            val items = append["continuationItems"] as? JsonArray ?: continue
            recognized = true
            visit(JsonObject(mapOf("contents" to items)))
        }
    }
    require(recognized) { "History response has no list" }
    return HistoryBrowsePage(sections, continuation?.takeIf(String::isNotBlank))
}

/**
 * A screen's finite history paging state. Overlapping pages merge within their date, while the same episode on a
 * different date remains visible. Validate cursors before changing anything, so a failed/cyclic page can be retried
 * without losing the already displayed archive.
 */
class HistoryPageAccumulator<T>(private val entryKey: (T) -> String) {
    var page: HistoryBrowsePage<T> = HistoryBrowsePage(emptyList())
        private set
    private val consumed = HashSet<String>()

    fun replace(first: HistoryBrowsePage<T>) {
        consumed.clear()
        page = merge(emptyList(), first)
    }

    fun append(token: String, next: HistoryBrowsePage<T>) {
        require(token == page.continuation && token !in consumed) { "Foreign history continuation" }
        require(next.continuation == null || (next.continuation != token && next.continuation !in consumed)) {
            "History continuation cycle"
        }
        val merged = merge(page.sections, next)
        consumed += token
        page = merged
    }

    private fun merge(before: List<HistoryBrowseSection<T>>, next: HistoryBrowsePage<T>): HistoryBrowsePage<T> {
        val result = before.toMutableList()
        for (section in next.sections) {
            val label = section.title.ifBlank { result.lastOrNull()?.title.orEmpty() }
            val index = result.indexOfFirst { it.title == label }
            val held = if (index < 0) emptyList() else result[index].entries
            val seen = held.mapTo(HashSet(), entryKey)
            val entries = held + section.entries.filter { seen.add(entryKey(it)) }
            if (entries.isEmpty()) continue
            val merged = HistoryBrowseSection(label, entries)
            if (index < 0) result += merged else result[index] = merged
        }
        return HistoryBrowsePage(result, next.continuation, next.paginationUnavailable)
    }
}

// JsonObject.obj comes from ProviderWire; the narrower helper below stays file-local.
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
