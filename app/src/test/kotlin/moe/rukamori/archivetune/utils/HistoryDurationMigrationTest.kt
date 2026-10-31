package moe.rukamori.archivetune.utils

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.constants.AppleMusicExperienceKey
import moe.rukamori.archivetune.constants.HISTORY_DURATION_LEGACY_FLOAT_KEY
import moe.rukamori.archivetune.constants.HISTORY_DURATION_MAX
import moe.rukamori.archivetune.constants.HISTORY_DURATION_MIN
import moe.rukamori.archivetune.constants.HistoryDuration
import moe.rukamori.archivetune.constants.UpdateChannelKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDurationMigrationTest {
    @Test fun floatIsReplacedByAnIntWithoutRemovingTheSameNamedKey() = runBlocking {
        val unknown = stringPreferencesKey("unrelated_setting")
        val old = preferencesOf(
            HISTORY_DURATION_LEGACY_FLOAT_KEY to 25.75f,
            UpdateChannelKey to "CANARY",
            AppleMusicExperienceKey to true,
            unknown to "retained",
        )

        assertTrue(HistoryDurationMigration.shouldMigrate(old))
        val migrated = HistoryDurationMigration.migrate(old)
        assertEquals(25, migrated[HistoryDuration])
        assertTrue(migrated.asMap()[HistoryDuration] is Int)
        assertEquals("CANARY", migrated[UpdateChannelKey])
        assertEquals(true, migrated[AppleMusicExperienceKey])
        assertEquals("retained", migrated[unknown])
        assertEquals(old.asMap().size, migrated.asMap().size)
        assertFalse(HistoryDurationMigration.shouldMigrate(migrated))
        assertSame(migrated, HistoryDurationMigration.migrate(migrated))
    }

    @Test fun alreadyStoredIntAndUnsetValueAreUntouched() = runBlocking {
        for (current in listOf(emptyPreferences(), preferencesOf(HistoryDuration to 42))) {
            assertFalse(HistoryDurationMigration.shouldMigrate(current))
            assertSame(current, HistoryDurationMigration.migrate(current))
        }
    }

    @Test fun legacyValuesKeepTheExistingTruncationAndBounds() = runBlocking {
        for ((old, expected) in listOf(-5f to HISTORY_DURATION_MIN, 100f to HISTORY_DURATION_MAX, 30.9f to 30)) {
            val migrated = HistoryDurationMigration.migrate(preferencesOf(HISTORY_DURATION_LEGACY_FLOAT_KEY to old))
            assertEquals(expected, migrated[HistoryDuration])
        }
    }
}
