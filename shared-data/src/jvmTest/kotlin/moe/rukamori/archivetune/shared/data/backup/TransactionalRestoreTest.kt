package moe.rukamori.archivetune.shared.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlin.test.*
import moe.rukamori.archivetune.shared.data.persistence.*
import moe.rukamori.archivetune.shared.domain.persistence.*
import moe.rukamori.archivetune.shared.domain.settings.*

class TransactionalRestoreTest {
    private val style = StringPreferenceKey("playerDesignStyle")
    private val catalog = StringPreferenceKey("spotify_sp_dc")
    private val streaming = StringPreferenceKey("spotifyStreamSpDc")
    private val pool = StringPreferenceKey("poolTidalAccounts")
    private val device = StringPreferenceKey("spotifyStreamDeviceId")
    private val scheduled = StringPreferenceKey("scheduledBackupDirectoryUri")
    private val vault = StringPreferenceKey("secure_store_fixture")
    private val inserted = StringPreferenceKey("newSetting")
    private val boolean = BooleanPreferenceKey("syntheticBoolean")
    private val integer = IntPreferenceKey("syntheticInt")
    private val long = LongPreferenceKey("syntheticLong")
    private val float = FloatPreferenceKey("syntheticFloat")
    private val double = DoublePreferenceKey("syntheticDouble")
    private val strings = StringSetPreferenceKey("syntheticSet")
    private val typedKeys: List<PreferenceKey<*>> = listOf(boolean, integer, long, float, double, strings)
    private fun before() = PreferenceSnapshot.of(style to "V4", catalog to "old-catalog", streaming to "old-stream",
        pool to "old-pool", device to "device-bound", scheduled to "local-directory", vault to "local-ciphertext",
        boolean to false, integer to -7, long to Long.MAX_VALUE, float to -0.0f,
        double to 1.25, strings to setOf("日本語", "old"))
    private fun after() = PreferenceSnapshot.of(style to "FLAMINGO", catalog to "new-catalog", streaming to "new-stream",
        pool to "new-pool", device to "foreign-device", scheduled to "foreign-directory", vault to "foreign-ciphertext",
        inserted to "new value", boolean to true, integer to 42, long to Long.MIN_VALUE,
        float to 3.5f, double to 9.25, strings to setOf("عربي", "new"))
    private fun fixture(): ByteArray = javaClass.getResourceAsStream("/fixtures/schema36-synthetic-song.db")!!.use { it.readBytes() }
    private fun archive(categories: Set<BackupCategory>): ByteArray =
        JvmBackupArchiveCodec().create(after(), categories, mapOf("song.db" to fixture()))

    @Test fun candidateConflictsNormalizeInsideTheRestoreCommitAndRollbackIsExact() = runTest {
        setup { _, prefs, _, restore ->
            val previous = prefs.snapshot()
            val incoming = after().toEditor().apply {
                this[DynamicThemeKey] = true
                this[RandomThemeOnStartupKey] = true
                this[AudioOffload] = true
                this[RejectSuspectedUpscaledAudioKey] = true
                this[MaxSongCacheSizeKey] = 0
                this[MaxImageCacheSizeKey] = 0
                this[SmartTrimmerKey] = true
            }.snapshot()
            val selected = setOf(BackupCategory.SETTINGS)
            val bytes = JvmBackupArchiveCodec().create(incoming, selected)
            prefs.failNextWrite = true
            assertFailsWith<java.io.IOException> { restore.restore(bytes, selected) }
            val rolledBack = prefs.snapshot()
            assertEquals(previous.keys, rolledBack.keys)
            previous.keys.forEach { assertEquals(previous[it], rolledBack[it], it.name) }

            restore.restore(bytes, selected)
            val restored = prefs.snapshot()
            assertEquals(true, restored[DynamicThemeKey])
            assertEquals(false, restored[RandomThemeOnStartupKey])
            assertEquals(false, restored[AudioOffload])
            assertEquals(true, restored[RejectSuspectedUpscaledAudioKey])
            assertEquals(false, restored[SmartTrimmerKey])
            assertEquals("device-bound", restored[device])
            assertEquals("new value", restored[inserted])
        }
    }

    /** Real AndroidX on disk, reopened after EVERY operation. Failure controls are test-only. */
    private class DiskPreferences(private val root: Path) : RestorePreferences {
        var failNextWrite = false
        var writesToFail = 0
        private suspend fun <T> withStore(block: suspend (DataStore<Preferences>) -> T): T {
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { root.resolve("settings.preferences_pb").toFile() })
            return try { block(store) } finally { withContext(NonCancellable) { job.cancelAndJoin() } }
        }
        override suspend fun snapshot(): PreferenceSnapshot = withStore { store ->
            PreferenceSnapshot.of(*store.data.first().asMap().map { (key, value) ->
                when (value) {
                    is Boolean -> BooleanPreferenceKey(key.name) to value
                    is Int -> IntPreferenceKey(key.name) to value
                    is Long -> LongPreferenceKey(key.name) to value
                    is Float -> FloatPreferenceKey(key.name) to value
                    is Double -> DoublePreferenceKey(key.name) to value
                    is String -> StringPreferenceKey(key.name) to value
                    is Set<*> -> {
                        require(value.all { it is String })
                        StringSetPreferenceKey(key.name) to value
                    }
                    else -> error("Unsupported test preference kind")
                }
            }.toTypedArray())
        }
        @Suppress("UNCHECKED_CAST")
        override suspend fun replaceAll(snapshot: PreferenceSnapshot) {
            withStore { store -> store.edit { target ->
                target.clear()
                snapshot.keys.forEach { key ->
                    when (key.kind) {
                        PreferenceKind.BOOLEAN -> target[booleanPreferencesKey(key.name)] = snapshot[key] as Boolean
                        PreferenceKind.INT -> target[intPreferencesKey(key.name)] = snapshot[key] as Int
                        PreferenceKind.LONG -> target[longPreferencesKey(key.name)] = snapshot[key] as Long
                        PreferenceKind.FLOAT -> target[floatPreferencesKey(key.name)] = snapshot[key] as Float
                        PreferenceKind.DOUBLE -> target[doublePreferencesKey(key.name)] = snapshot[key] as Double
                        PreferenceKind.STRING -> target[stringPreferencesKey(key.name)] = snapshot[key] as String
                        PreferenceKind.STRING_SET -> target[stringSetPreferencesKey(key.name)] = snapshot[key] as Set<String>
                    }
                }
            } }
            if (failNextWrite) { failNextWrite = false; throw java.io.IOException("synthetic post-commit failure") }
            if (writesToFail > 0) { writesToFail--; throw java.io.IOException("synthetic persistent preference failure") }
        }
    }
    private class DatabaseHost(private val root: Path) : RestoreLifecycle {
        private val mutex = Mutex()
        var connection: SQLiteConnection? = null
        var failReopenOnce = false
        var cancelReopenOnce = false
        var reopenCount = 0
        var pauseReopen: CompletableDeferred<Unit>? = null
        override suspend fun <T> withExclusiveAccess(operation: suspend () -> T): T = mutex.withLock { operation() }
        override suspend fun closeDatabase() { connection?.close(); connection = null }
        override suspend fun reopenAndValidateDatabase() {
            connection = BundledSQLiteDriver().open(root.resolve("song.db").toString())
            reopenCount++
            connection!!.prepare("PRAGMA integrity_check").use { require(it.step() && it.getText(0) == "ok") }
            if (failReopenOnce) { failReopenOnce = false; throw java.io.IOException("synthetic reopened candidate failure") }
            if (cancelReopenOnce) { cancelReopenOnce = false; throw CancellationException("synthetic cancellation") }
            pauseReopen?.let { pauseReopen = null; it.complete(Unit); awaitCancellation() }
        }
        fun title(): String = connection!!.prepare("SELECT title FROM song WHERE id = 'fixture-song'").use { check(it.step()); it.getText(0) }
    }
    private suspend fun setup(block: suspend (Path, DiskPreferences, DatabaseHost, JvmTransactionalRestore) -> Unit) {
        val root = Files.createTempDirectory("transactional-restore-test")
        val host = DatabaseHost(root)
        try {
            Files.createDirectory(root.resolve("fonts"))
            Files.write(root.resolve("song.db"), fixture())
            BundledSQLiteDriver().open(root.resolve("song.db").toString()).let { db ->
                try { db.prepare("UPDATE song SET title = 'Old install'").use { it.step() } } finally { db.close() }
            }
            val prefs = DiskPreferences(root); prefs.replaceAll(before())
            Files.writeString(root.resolve("vault-keystore.bin"), "device-bound-keystore-fixture")
            host.reopenAndValidateDatabase()
            block(root, prefs, host, JvmTransactionalRestore(root, root.resolve("fonts"), prefs, host))
        } finally { host.closeDatabase(); root.toFile().deleteRecursively() }
    }
    @Test fun appliesSelectedCategoriesReopensSqliteAndPreservesDeviceAndSeparateAccounts() = runTest {
        setup { root, prefs, host, restore ->
            restore.restore(archive(setOf(BackupCategory.LIBRARY, BackupCategory.SETTINGS)), setOf(BackupCategory.LIBRARY, BackupCategory.SETTINGS))
            assertEquals("Synthetic schema-36 日本語", host.title())
            val reopened = DiskPreferences(root).snapshot()
            assertEquals("FLAMINGO", reopened[style]); assertEquals("new value", reopened[inserted])
            assertEquals(typedKeys.associateWith { after()[it] }, typedKeys.associateWith { reopened[it] })
            assertEquals("old-catalog", reopened[catalog]); assertEquals("old-stream", reopened[streaming]); assertEquals("old-pool", reopened[pool])
            assertEquals("device-bound", reopened[device]); assertEquals("local-directory", reopened[scheduled])
            assertEquals("local-ciphertext", reopened[vault])
            assertEquals("device-bound-keystore-fixture", Files.readString(root.resolve("vault-keystore.bin")))
            restore.restore(archive(setOf(BackupCategory.ACCOUNT)), setOf(BackupCategory.ACCOUNT))
            assertEquals("new-catalog", prefs.snapshot()[catalog]); assertEquals("new-stream", prefs.snapshot()[streaming])
            assertEquals("new-pool", prefs.snapshot()[pool]); assertEquals("device-bound", prefs.snapshot()[device])
            assertEquals("FLAMINGO", prefs.snapshot()[style])
        }
    }
    @Test fun failureAfterPreferenceCommitRollsBackFilesNewKeysAndDatabaseReopen() = runTest {
        setup { root, prefs, host, restore ->
            prefs.failNextWrite = true
            assertFailsWith<java.io.IOException> { restore.restore(archive(BackupCategory.entries.toSet()), BackupCategory.entries.toSet()) }
            assertEquals("Old install", host.title())
            assertEquals(before().keys.associateWith { before()[it] }, prefs.snapshot().keys.associateWith { prefs.snapshot()[it] })
            assertNull(DiskPreferences(root).snapshot()[inserted])
            assertTrue(Files.list(root).use { files -> files.noneMatch { it.fileName.toString().startsWith(".restore-") } })
        }
    }
    @Test fun failureOrCancellationDuringCandidateReopenRollsBackBeforeHostResumes() = runTest {
        setup { _, prefs, host, restore ->
            host.failReopenOnce = true
            assertFailsWith<java.io.IOException> { restore.restore(archive(BackupCategory.entries.toSet()), BackupCategory.entries.toSet()) }
            assertEquals("Old install", host.title()); assertEquals("V4", prefs.snapshot()[style])
            host.cancelReopenOnce = true
            assertFailsWith<CancellationException> { restore.restore(archive(BackupCategory.entries.toSet()), BackupCategory.entries.toSet()) }
            assertEquals("Old install", host.title()); assertEquals("V4", prefs.snapshot()[style])
        }
    }
    @Test fun invalidArchiveDoesNotCloseHostOrTouchAnyPersistedState() = runTest {
        setup { root, prefs, host, restore ->
            val bytes = Files.readAllBytes(root.resolve("song.db"))
            assertFailsWith<IllegalArgumentException> { restore.restore(byteArrayOf(1), BackupCategory.entries.toSet()) }
            assertContentEquals(bytes, Files.readAllBytes(root.resolve("song.db")))
            assertEquals(1, host.reopenCount); assertEquals("V4", prefs.snapshot()[style])
        }
    }
    @Test fun externalCancellationWaitsForRollbackAndOriginalDatabaseReopen() = runTest {
        setup { _, prefs, host, restore ->
            val reached = CompletableDeferred<Unit>()
            host.pauseReopen = reached
            val job = launch { restore.restore(archive(BackupCategory.entries.toSet()), BackupCategory.entries.toSet()) }
            reached.await()
            job.cancelAndJoin()
            assertEquals("Old install", host.title()); assertEquals("V4", prefs.snapshot()[style])
            assertNull(prefs.snapshot()[inserted])
        }
    }
    @Test fun rollbackFailureRetainsRecoveryFilesAndRefusesFurtherRestore() = runTest {
        setup { _, prefs, host, restore ->
            prefs.writesToFail = 2
            val failure = assertFailsWith<RestoreRollbackFailure> {
                restore.restore(archive(BackupCategory.entries.toSet()), BackupCategory.entries.toSet())
            }
            assertTrue(Files.isRegularFile(failure.recoveryDirectory.resolve("old-0")))
            assertNull(host.connection)
            assertFailsWith<RestoreRollbackFailure> { restore.restore(archive(setOf(BackupCategory.SETTINGS)), setOf(BackupCategory.SETTINGS)) }
        }
    }
    @Test fun restoresRealWalSnapshotWithoutLosingUncheckpointedLibraryChanges() = runTest {
        val candidate = Files.createTempDirectory("candidate-wal-fixture")
        try {
            Files.write(candidate.resolve("song.db"), fixture())
            val db = BundledSQLiteDriver().open(candidate.resolve("song.db").toString())
            try {
                db.prepare("PRAGMA journal_mode = WAL").use { assertTrue(it.step()); assertEquals("wal", it.getText(0)) }
                db.prepare("PRAGMA wal_autocheckpoint = 0").use { it.step() }
                db.prepare("UPDATE song SET title = 'Candidate WAL value'").use { it.step() }
                val payload = listOf("song.db", "song.db-wal", "song.db-shm").associateWith { Files.readAllBytes(candidate.resolve(it)) }
                assertTrue(payload.getValue("song.db-wal").isNotEmpty())
                val bytes = JvmBackupArchiveCodec().create(PreferenceSnapshot.EMPTY, setOf(BackupCategory.LIBRARY), payload)
                setup { _, prefs, host, restore ->
                    restore.restore(bytes, setOf(BackupCategory.LIBRARY))
                    assertEquals("Candidate WAL value", host.title())
                    assertEquals("V4", prefs.snapshot()[style]); assertEquals("old-catalog", prefs.snapshot()[catalog])
                }
            } finally { db.close() }
        } finally { candidate.toFile().deleteRecursively() }
    }
    private fun font(): ByteArray = javaClass.getResourceAsStream("/retained-jost-fixture.ttf")!!.use { it.readBytes() }
    @Test fun validFontsCommitAndRollbackRemovesIntroducedFontWhilePreservingUnselectedOnes() = runTest {
        setup { root, prefs, _, restore ->
            val keep = root.resolve("fonts/keep.ttf")
            Files.write(keep, font())
            val installed = root.resolve("fonts/installed.ttf")
            val bytes = JvmBackupArchiveCodec().create(after(), setOf(BackupCategory.SETTINGS), fonts = mapOf("installed.ttf" to font()))
            prefs.failNextWrite = true
            assertFailsWith<java.io.IOException> { restore.restore(bytes, setOf(BackupCategory.SETTINGS)) }
            assertFalse(Files.exists(installed)); assertContentEquals(font(), Files.readAllBytes(keep))
            assertEquals("V4", prefs.snapshot()[style]); assertNull(prefs.snapshot()[inserted])
            restore.restore(bytes, setOf(BackupCategory.SETTINGS))
            assertContentEquals(font(), Files.readAllBytes(installed))
            assertContentEquals(font(), Files.readAllBytes(keep))
            assertEquals("FLAMINGO", prefs.snapshot()[style])
        }
    }
    @Test fun actualFilesystemStagingFailureLeavesHostOpenAndEverySelectedTargetUnmodified() = runTest {
        setup { root, prefs, host, restore ->
            val original = Files.readAllBytes(root.resolve("song.db"))
            Files.delete(root.resolve("fonts")); Files.writeString(root.resolve("fonts"), "unavailable directory")
            val bytes = JvmBackupArchiveCodec().create(after(), setOf(BackupCategory.LIBRARY, BackupCategory.SETTINGS),
                mapOf("song.db" to fixture()), mapOf("installed.ttf" to font()))
            assertFailsWith<java.io.IOException> { restore.restore(bytes, setOf(BackupCategory.LIBRARY, BackupCategory.SETTINGS)) }
            assertContentEquals(original, Files.readAllBytes(root.resolve("song.db")))
            assertEquals(1, host.reopenCount); assertEquals("Old install", host.title())
            assertEquals("V4", prefs.snapshot()[style])
            assertTrue(Files.list(root).use { files -> files.noneMatch { it.fileName.toString().startsWith(".restore-") } })
        }
    }
}
