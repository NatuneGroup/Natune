package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * Queue-behaviour switches shared with the portable hosts. The persisted names match
 * shared-domain's PersistedPreferenceKeys verbatim, so a backup written by one host restores on
 * every other.
 */
val PreventDuplicateTracksInQueueKey = booleanPreferencesKey("preventDuplicateTracksInQueue")
val QueueLockedKey = booleanPreferencesKey("queueLocked")
