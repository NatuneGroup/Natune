package moe.rukamori.archivetune.utils

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import moe.rukamori.archivetune.constants.HistoryDuration
import moe.rukamori.archivetune.shared.domain.settings.migrateHistoryDuration

internal object HistoryDurationMigration : DataMigration<Preferences> {
    // AndroidX keys compare by name, not kind. A typed get cannot distinguish these two versions.
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.asMap()[HistoryDuration] is Float

    override suspend fun migrate(currentData: Preferences): Preferences =
        if (shouldMigrate(currentData)) currentData.editSharedPreferences { migrateHistoryDuration() } else currentData

    override suspend fun cleanUp() = Unit
}
