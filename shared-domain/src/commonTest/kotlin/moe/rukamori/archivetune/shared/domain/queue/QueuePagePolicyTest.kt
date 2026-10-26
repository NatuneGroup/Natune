/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

import kotlin.test.Test
import kotlin.test.assertEquals

/** Characterizes which items of a continuation page are appended. Mirrors the Android QueuePagePolicy tests. */
class QueuePagePolicyTest {
    @Test
    fun continuationPageKeepsEveryNewItem() {
        assertEquals(
            listOf("b", "c"),
            QueuePagePolicy.appendableQueuePage(listOf("b", "c"), repeatsCurrentItem = false),
        )
    }

    @Test
    fun overlappingRadioPageDropsTheRepeatedCurrentItem() {
        assertEquals(
            listOf("c"),
            QueuePagePolicy.appendableQueuePage(listOf("current", "c"), repeatsCurrentItem = true),
        )
    }

    @Test
    fun onlyTheFirstCopyOfTheCurrentItemIsDropped() {
        assertEquals(
            listOf("current", "c"),
            QueuePagePolicy.appendableQueuePage(listOf("current", "current", "c"), repeatsCurrentItem = true),
        )
    }

    @Test
    fun emptyPagesStaySafeEitherWay() {
        assertEquals(emptyList<String>(), QueuePagePolicy.appendableQueuePage(emptyList(), repeatsCurrentItem = true))
        assertEquals(emptyList<String>(), QueuePagePolicy.appendableQueuePage(emptyList(), repeatsCurrentItem = false))
    }
}
