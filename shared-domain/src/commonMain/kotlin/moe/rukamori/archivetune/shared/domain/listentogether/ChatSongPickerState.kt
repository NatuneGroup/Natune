package moe.rukamori.archivetune.shared.domain.listentogether

import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

/**
 * The chat song picker's search state. The paging fields mirror the shared paged presentation so a host maps
 * them one to one: [loading] covers the debounce window and any page in flight, [failed] keeps the rows already
 * shown behind a retry, and [initialized] turns true once a page of the current query has been published.
 */
data class ChatSongPickerState(
    val query: String = "",
    val items: List<ContentOccurrence> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val initialized: Boolean = false,
    val failed: Boolean = false,
)
