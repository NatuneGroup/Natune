package moe.rukamori.archivetune.shared.data.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataLimits
import moe.rukamori.archivetune.shared.domain.settings.BooleanPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.DoublePreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.FloatPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.IntPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.LongPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.StringPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.StringSetPreferenceKey

/**
 * The shared archive codec's own checks: the manifest gate both hosts answer, the typed settings round-trip, the
 * policy-filtered subsets, the pending marker and the archive parse. The ZIP container itself is covered by the
 * host codec tests.
 */
class PortableBackupArchiveTest {
    private fun snapshot(vararg entries: Pair<*, *>): PreferenceSnapshot = PreferenceSnapshot.of(
        *entries.map { (key, value) -> key as PreferenceKey<*> to value as Any }
            .toTypedArray(),
    )

    @Test
    fun settingsRoundTripKeepsKindsAndBits() {
        val snapshot = snapshot(
            BooleanPreferenceKey("enabled") to true,
            StringPreferenceKey("title") to "héllo",
            IntPreferenceKey("count") to 42,
            LongPreferenceKey("size") to 9_999_999_999L,
            FloatPreferenceKey("level") to Float.NaN,
            DoublePreferenceKey("ratio") to Double.POSITIVE_INFINITY,
            StringSetPreferenceKey("tags") to setOf("b", "a"),
        )
        val decoded = PortableBackupFormat.decodeSettings(PortableBackupFormat.encodeSettings(snapshot))
        assertEquals(snapshot.keys, decoded.keys)
        assertEquals(true, decoded[BooleanPreferenceKey("enabled")])
        assertEquals("héllo", decoded[StringPreferenceKey("title")])
        assertEquals(42, decoded[IntPreferenceKey("count")])
        assertEquals(9_999_999_999L, decoded[LongPreferenceKey("size")])
        assertEquals(Float.NaN.toRawBits(), decoded[FloatPreferenceKey("level")]?.toRawBits())
        assertEquals(Double.POSITIVE_INFINITY.toRawBits(), decoded[DoublePreferenceKey("ratio")]?.toRawBits())
        assertEquals(setOf("a", "b"), decoded[StringSetPreferenceKey("tags")])
    }

    @Test
    fun manifestAcceptsBothPortablePlatforms() {
        PortableBackupFormat.checkManifest(PortableBackupFormat.manifest(PortableBackupFormat.PLATFORM_DESKTOP, 1L))
        PortableBackupFormat.checkManifest(PortableBackupFormat.manifest(PortableBackupFormat.PLATFORM_IOS, 1L))
    }

    @Test
    fun manifestRejectsForeignFormatAndPlatform() {
        assertProblem(BackupArchiveProblem.NOT_A_BACKUP) {
            PortableBackupFormat.checkManifest("""{"format":"other","platform":"IOS"}""".encodeToByteArray())
        }
        assertProblem(BackupArchiveProblem.NOT_A_BACKUP) {
            PortableBackupFormat.checkManifest(
                """{"format":"natune.desktop.backup","platform":"ANDROID"}""".encodeToByteArray(),
            )
        }
    }

    @Test
    fun manifestRejectsNewerAndMissingVersions() {
        assertProblem(BackupArchiveProblem.CORRUPTED) {
            PortableBackupFormat.checkManifest(
                """{"format":"natune.desktop.backup","platform":"IOS","formatVersion":99,"databaseVersion":1}"""
                    .encodeToByteArray(),
            )
        }
        assertProblem(BackupArchiveProblem.CORRUPTED) {
            PortableBackupFormat.checkManifest(
                """{"format":"natune.desktop.backup","platform":"IOS"}""".encodeToByteArray(),
            )
        }
    }

    @Test
    fun settingsSubsetKeepsOnlyChosenPolicySections() {
        val snapshot = snapshot(
            StringPreferenceKey("innerTubeCookie") to "cookie",
            StringPreferenceKey("theme") to "dark",
            IntPreferenceKey("scheduledBackupEnabled") to 1,
            StringPreferenceKey("secure_store_blob") to "blob",
        )
        val accountOnly = settingsSubset(snapshot, setOf(BackupDataCategory.ACCOUNT))!!
        assertEquals(setOf(StringPreferenceKey("innerTubeCookie")), accountOnly.keys.toSet())
        val settingsOnly = settingsSubset(snapshot, setOf(BackupDataCategory.SETTINGS))!!
        assertEquals(setOf(StringPreferenceKey("theme")), settingsOnly.keys.toSet())
        assertNull(settingsSubset(snapshot, setOf(BackupDataCategory.LIBRARY)))
    }

    @Test
    fun mergedRestoredSettingsDropsAndKeepsByPolicy() {
        val current = snapshot(
            StringPreferenceKey("innerTubeCookie") to "old",
            StringPreferenceKey("theme") to "light",
            StringPreferenceKey("spotifyStreamDeviceId") to "device",
        )
        val restored = snapshot(
            StringPreferenceKey("innerTubeCookie") to "new",
            StringPreferenceKey("theme") to "dark",
        )
        val merged = mergedRestoredSettings(current, restored, setOf(BackupCategory.ACCOUNT))
        assertEquals("new", merged[StringPreferenceKey("innerTubeCookie")])
        // SETTINGS was not owned, so the restored theme does not land; the device key is never archive-owned.
        assertEquals("light", merged[StringPreferenceKey("theme")])
        assertEquals("device", merged[StringPreferenceKey("spotifyStreamDeviceId")])
    }

    @Test
    fun archiveCreateParseRoundTrip() {
        val database = "SQLite format 3\u0000portable".encodeToByteArray()
        val settings = snapshot(StringPreferenceKey("theme") to "dark")
        val archive = PortableBackupArchive.create(
            PortableBackupFormat.PLATFORM_IOS, database, settings, nowMillis = 1_700_000_000_000L,
        )
        val parsed = PortableBackupArchive.parse(archive)
        assertContentEquals(database, parsed.database)
        assertEquals("dark", parsed.settings?.get(StringPreferenceKey("theme")))
        assertEquals(
            setOf(BackupDataCategory.LIBRARY, BackupDataCategory.SETTINGS),
            parsed.categories,
        )
    }

    @Test
    fun archiveParsesDesktopWrittenManifest() {
        val archive = PortableBackupArchive.create(
            PortableBackupFormat.PLATFORM_DESKTOP, null,
            snapshot(StringPreferenceKey("theme") to "dark"), nowMillis = 0L,
        )
        assertEquals(setOf(BackupDataCategory.SETTINGS), PortableBackupArchive.parse(archive).categories)
    }

    @Test
    fun parseRejectsManifestlessAndForeignEntries() {
        assertProblem(BackupArchiveProblem.NOT_A_BACKUP) { PortableBackupArchive.parse(byteArrayOf()) }
        assertProblem(BackupArchiveProblem.NOT_A_BACKUP) {
            PortableBackupArchive.parse("not a zip at all".encodeToByteArray())
        }
        // An Android-format archive carries song.db and settings.xml, never the portable manifest.
        val androidShaped = PortableZip.write(
            listOf("song.db" to byteArrayOf(1), "settings.xml" to byteArrayOf(2)), nowMillis = 0L,
        )
        assertProblem(BackupArchiveProblem.NOT_A_BACKUP) { PortableBackupArchive.parse(androidShaped) }
    }

    @Test
    fun parseRejectsUnownedPreferenceKeys() {
        val settings = snapshot(StringPreferenceKey("secure_store_blob") to "blob")
        val archive = PortableBackupArchive.create(
            PortableBackupFormat.PLATFORM_IOS, null, settings, nowMillis = 0L,
        )
        // The crafted key is not exported by the policy, so the archive it made reads as having no restorable data.
        assertProblem(BackupArchiveProblem.CORRUPTED) { PortableBackupArchive.parse(archive) }
    }

    @Test
    fun oversizedArchiveIsAStorageRefusal() {
        val tooBig = ByteArray(DriveAppDataLimits.MAX_ARCHIVE_BYTES + 1)
        assertProblem(BackupArchiveProblem.INSUFFICIENT_STORAGE) { PortableBackupArchive.parse(tooBig) }
    }

    @Test
    fun pendingMarkerRoundTrip() {
        val categories = setOf(BackupDataCategory.LIBRARY, BackupDataCategory.SETTINGS)
        assertEquals(categories, PortableBackupFormat.decodePendingMarker(PortableBackupFormat.encodePendingMarker(categories)))
        assertNull(PortableBackupFormat.decodePendingMarker("{not json".encodeToByteArray()))
        assertNull(PortableBackupFormat.decodePendingMarker("""{"categories":["BOGUS"]}""".encodeToByteArray()))
    }

    private fun assertProblem(problem: BackupArchiveProblem, block: () -> Unit) {
        val failure = assertFailsWith<BackupArchiveException> { block() }
        assertEquals(problem, failure.problem)
    }
}
