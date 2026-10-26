/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.recognition

import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryEntry

/** The baseline keeps the newest 50 saved recognitions and drops older ones when a new match is saved. */
const val RECOGNITION_HISTORY_LIMIT: Int = 50

/**
 * The new entry first, with any earlier save of the same identity removed, capped at [RECOGNITION_HISTORY_LIMIT].
 * Adapters apply this inside their atomic edit so two saves cannot interleave and drop each other's entry.
 */
fun mergeRecognitionHistory(
    current: List<RecognitionHistoryEntry>,
    entry: RecognitionHistoryEntry,
): List<RecognitionHistoryEntry> =
    buildList {
        add(entry)
        addAll(current.filterNot { it.stableKey == entry.stableKey })
    }.take(RECOGNITION_HISTORY_LIMIT)

/** Newest first by recognition time. The sort is stable, so stored order still decides equal timestamps. */
fun sortRecognitionHistoryNewestFirst(entries: List<RecognitionHistoryEntry>): List<RecognitionHistoryEntry> =
    entries.sortedByDescending { it.recognizedAtEpochMillis }
