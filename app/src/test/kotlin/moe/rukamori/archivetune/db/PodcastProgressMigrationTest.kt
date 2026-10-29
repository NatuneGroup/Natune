package moe.rukamori.archivetune.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastProgressMigrationTest {
    @Test fun migrationIsAnAdditiveSelfContainedThirtySixToThirtySevenStep() {
        val statements = ArrayList<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, arguments ->
            check(method.name == "execSQL") { "Migration must not read, rewrite or drop a user's legacy rows" }
            statements += arguments!![0] as String
            null
        } as SupportSQLiteDatabase

        assertEquals(36, PodcastProgressMigration.startVersion)
        assertEquals(37, PodcastProgressMigration.endVersion)
        PodcastProgressMigration.migrate(database)
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `podcast_progress` (`episodeId` TEXT NOT NULL, " +
                "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER, `finished` INTEGER NOT NULL, " +
                "`updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`episodeId`))",
            statements.single(),
        )
        // Running the same add step during recovery is harmless, with no reset or foreign key to song.
        PodcastProgressMigration.migrate(database)
        assertEquals(statements[0], statements[1])
        assertTrue(statements.all { it.startsWith("CREATE TABLE IF NOT EXISTS") })
    }

    @Test fun entityConversionKeepsUnknownDurationAndAPlayedMarker() {
        val row = PodcastProgressEntity("youtube:episode", 0L, null, true, 12L).progress()
        assertEquals("youtube:episode", row.episodeId)
        assertEquals(null, row.durationMs)
        assertTrue(row.finished)
        assertEquals(0L, row.resumePositionMs)
        assertEquals(12L, row.updatedAtMs)
    }
}
