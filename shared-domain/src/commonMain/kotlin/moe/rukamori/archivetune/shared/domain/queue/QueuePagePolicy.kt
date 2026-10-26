package moe.rukamori.archivetune.shared.domain.queue

object QueuePagePolicy {
    /**
     * The part of a continuation page that may be appended. A radio page can repeat the song that is
     * already playing as its first item, so that copy is dropped rather than queued twice.
     */
    fun <T> appendableQueuePage(items: List<T>, repeatsCurrentItem: Boolean): List<T> =
        if (repeatsCurrentItem) items.drop(1) else items
}
