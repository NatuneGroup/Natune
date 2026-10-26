package moe.rukamori.archivetune.shared.domain.settings

import kotlinx.coroutines.flow.StateFlow

/** Null means the initial persisted read has not completed, not a fresh installation. */
interface SettingsStore {
    val snapshots: StateFlow<PreferenceSnapshot?>

    /**
     * Reads, transforms and persists one snapshot atomically. Implementations serialize this with every
     * other writer and publish only after durable commit. A failed edit neither publishes nor prompts.
     * [change] must be side-effect free: a storage implementation may retry it.
     */
    suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange
}

data class SettingsChange(
    val before: PreferenceSnapshot,
    val after: PreferenceSnapshot,
) {
    val changedKeys: Set<PreferenceKey<*>>
        get() = (before.keys + after.keys).filterTo(linkedSetOf()) { before[it] != after[it] }
}
