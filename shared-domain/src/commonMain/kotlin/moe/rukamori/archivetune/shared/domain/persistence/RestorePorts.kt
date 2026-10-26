/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.persistence

import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/** The host performs one atomic typed edit; this includes removal when restoring a rollback snapshot. */
interface RestorePreferences {
    suspend fun snapshot(): PreferenceSnapshot
    suspend fun replaceAll(snapshot: PreferenceSnapshot)
}

/**
 * The host quiesces ALL DB, preference and playback-persistence writers/readers for the operation.
 * A rollback failure must leave that host unavailable until explicit recovery, not resume mixed state.
 */
interface RestoreLifecycle {
    suspend fun <T> withExclusiveAccess(operation: suspend () -> T): T
    suspend fun closeDatabase()
    suspend fun reopenAndValidateDatabase()
}
