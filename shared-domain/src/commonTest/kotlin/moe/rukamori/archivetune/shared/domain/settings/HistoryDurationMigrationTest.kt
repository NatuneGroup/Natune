package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryDurationMigrationTest {
    @Test fun loadAndRestoreNormalizationConvertLegacyFloatOnce() {
        val editor = PreferenceSnapshot.of(HISTORY_DURATION_LEGACY_FLOAT_KEY to 24.8f).toEditor()
        editor.normalizeSettingsConflicts()
        assertEquals(24, editor[HistoryDuration])
        assertNull(editor[HISTORY_DURATION_LEGACY_FLOAT_KEY])
        editor.normalizeSettingsConflicts()
        assertEquals(24, editor[HistoryDuration])
    }

    @Test fun currentSelectionWinsIfAHostRetainedBothKinds() {
        val editor = PreferenceSnapshot.of(
            HISTORY_DURATION_LEGACY_FLOAT_KEY to 15f,
            HistoryDuration to 45,
        ).toEditor()
        editor.migrateHistoryDuration()
        assertEquals(45, editor[HistoryDuration])
        assertNull(editor[HISTORY_DURATION_LEGACY_FLOAT_KEY])
    }

    @Test fun onlyLegacyValuesAreClamped() {
        for ((value, expected) in listOf(-1f to 1, 90f to 60, 20.9f to 20)) {
            val editor = PreferenceSnapshot.of(HISTORY_DURATION_LEGACY_FLOAT_KEY to value).toEditor()
            editor.migrateHistoryDuration()
            assertEquals(expected, editor[HistoryDuration])
        }
        val current = PreferenceSnapshot.of(HistoryDuration to 70).toEditor()
        current.migrateHistoryDuration()
        assertEquals(70, current[HistoryDuration])
    }
}
