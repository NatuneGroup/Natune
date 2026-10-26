/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

object QueuePagePolicy {
    /**
     * The part of a continuation page that may be appended. A radio page can repeat the song that is
     * already playing as its first item, so that copy is dropped rather than queued twice.
     */
    fun <T> appendableQueuePage(items: List<T>, repeatsCurrentItem: Boolean): List<T> =
        if (repeatsCurrentItem) items.drop(1) else items
}
