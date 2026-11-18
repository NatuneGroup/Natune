
package moe.rukamori.archivetune.playback.queues

fun <T> appendableQueuePage(items: List<T>, repeatsCurrentItem: Boolean): List<T> =
    if (repeatsCurrentItem) items.drop(1) else items
