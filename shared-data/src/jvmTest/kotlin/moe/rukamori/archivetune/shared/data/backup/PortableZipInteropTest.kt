package moe.rukamori.archivetune.shared.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.StringPreferenceKey

/**
 * The byte-compat contract: archives the shared ZIP writer emits must read through java.util.zip, which is what the
 * desktop host parses with, and archives java's ZipOutputStream writes must read back through [PortableZip.read],
 * including the data-descriptor form ZipOutputStream produces when entry sizes are not known up front.
 */
class PortableZipInteropTest {
    @Test
    fun writtenArchiveReadsThroughJavaZip() {
        val entries = listOf(
            "backup.json" to """{"format":"natune.desktop.backup"}""".encodeToByteArray(),
            "portable-state.db" to ByteArray(2048) { (it * 31).toByte() },
            "settings.json" to "{}".encodeToByteArray(),
            "ünïcode.json" to "utf8-name".encodeToByteArray(),
        )
        val archive = PortableZip.write(entries, nowMillis = 1_700_000_000_000L)
        val readBack = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                readBack += entry.name to zip.readBytes()
            }
        }
        assertEquals(entries.map { it.first }, readBack.map { it.first })
        entries.forEachIndexed { index, (_, data) -> assertContentEquals(data, readBack[index].second) }
    }

    @Test
    fun javaWrittenArchiveReadsThroughPortableZip() {
        // ZipOutputStream writes the data-descriptor form: sizes and CRC live after the data, not in the local header.
        val entries = listOf(
            "backup.json" to """{"format":"x"}""".encodeToByteArray(),
            "portable-state.db" to ByteArray(4096) { (it * 7).toByte() },
            "settings.json" to """{"entries":[]}""".encodeToByteArray(),
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        val read = PortableZip.read(output.toByteArray(), maxEntries = 16, { Long.MAX_VALUE }, Long.MAX_VALUE)
        assertEquals(entries.map { it.first }, read.map { it.name })
        entries.forEachIndexed { index, (_, data) -> assertContentEquals(data, read[index].data) }
    }

    @Test
    fun createdBackupParsesThroughJavaZip() {
        val settings = PreferenceSnapshot.of(StringPreferenceKey("theme") to "dark")
        val archive = PortableBackupArchive.create(
            PortableBackupFormat.PLATFORM_IOS, ByteArray(1024) { 0x55 }, settings, nowMillis = 1_700_000_000_000L,
        )
        val names = ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        assertEquals(
            listOf(
                PortableBackupFormat.MANIFEST,
                PortableBackupFormat.DATABASE,
                PortableBackupFormat.SETTINGS,
            ),
            names,
        )
    }

    @Test
    fun corruptArchiveIsNotABackup() {
        val failure = assertFailsWith<BackupArchiveException> {
            PortableBackupArchive.parse(ByteArray(256) { 0x7F })
        }
        assertEquals(BackupArchiveProblem.NOT_A_BACKUP, failure.problem)
    }

    @Test
    fun tamperedEntryFailsIntegrity() {
        val archive = PortableZip.write(
            listOf("backup.json" to """{"format":"natune.desktop.backup"}""".encodeToByteArray()), nowMillis = 0L,
        )
        // Flip a payload byte without touching the CRC: the entry then fails its own check.
        val dataOffset = 30 + "backup.json".encodeToByteArray().size
        archive[dataOffset] = (archive[dataOffset].toInt() xor 0xFF).toByte()
        val failure = assertFailsWith<BackupArchiveException> { PortableBackupArchive.parse(archive) }
        assertEquals(BackupArchiveProblem.NOT_A_BACKUP, failure.problem)
    }

    @Test
    fun emptyEntryRoundTripsBothWays() {
        val archive = PortableZip.write(
            listOf("empty.bin" to byteArrayOf(), "full.bin" to ByteArray(64) { it.toByte() }), nowMillis = 0L,
        )
        val names = ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name to zip.readBytes() }.toList()
        }
        assertContentEquals(byteArrayOf(), names.first { it.first == "empty.bin" }.second)

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("empty.bin"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("full.bin"))
            zip.write(ByteArray(32) { 0x55 })
            zip.closeEntry()
        }
        val read = PortableZip.read(output.toByteArray(), 16, { Long.MAX_VALUE }, Long.MAX_VALUE)
        assertContentEquals(byteArrayOf(), read.first { it.name == "empty.bin" }.data)
        assertContentEquals(ByteArray(32) { 0x55 }, read.first { it.name == "full.bin" }.data)
    }
}
