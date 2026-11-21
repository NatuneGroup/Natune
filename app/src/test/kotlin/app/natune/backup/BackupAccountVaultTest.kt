package app.natune.backup

import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.settings.StringPreferenceKey
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupAccountVaultTest {
    private val wrong = "wrong password"
    private val accounts = PreferenceSnapshot.EMPTY.toEditor().apply {
        this[StringPreferenceKey("innerTubeCookie")] = "SAPISID=fixture"
        this[StringPreferenceKey("accountName")] = "Fixture"
    }.snapshot()

    @Test
    fun sealedSignInsOpenWithTheirPasswordAndNotInPlainText() {
        val sealed = BackupAccountVault.seal(accounts, "correct horse")

        assertFalse(String(sealed).contains("SAPISID"))
        val opened = BackupAccountVault.open(sealed, "correct horse", wrong)
        assertEquals("SAPISID=fixture", opened[StringPreferenceKey("innerTubeCookie")])
        assertEquals("Fixture", opened[StringPreferenceKey("accountName")])
    }

    @Test
    fun aWrongPasswordOrAlteredEnvelopeIsReportedTheSameWay() {
        val sealed = BackupAccountVault.seal(accounts, "correct horse")
        assertEquals(wrong, assertThrows(BackupPasswordException::class.java) {
            BackupAccountVault.open(sealed, "battery staple", wrong)
        }.message)
        // Lowering the iteration count is caught by the authenticated header, not trusted.
        val weakened = String(sealed).replace(Regex("\"iterations\":\\d+"), "\"iterations\":1").toByteArray()
        assertThrows(BackupPasswordException::class.java) { BackupAccountVault.open(weakened, "correct horse", wrong) }
        assertThrows(BackupPasswordException::class.java) { BackupAccountVault.open("{}".toByteArray(), "correct horse", wrong) }
    }

    @Test
    fun onlySignInKeysLeaveTheVaultEvenIfThePayloadCarriesMore() {
        val mixed = accounts.toEditor().apply { this[StringPreferenceKey("darkMode")] = "ON" }.snapshot()
        val opened = BackupAccountVault.open(BackupAccountVault.seal(mixed, "pw"), "pw", wrong)
        assertNull(opened[StringPreferenceKey("darkMode")])
        assertEquals("SAPISID=fixture", opened[StringPreferenceKey("innerTubeCookie")])
    }

    @Test
    fun signInsMergeIntoStagedSettingsAndCreateThemWhenMissing() {
        val dir = Files.createTempDirectory("vault").toFile()
        val xml = File(dir, "settings.xml")
        xml.outputStream().use {
            writeLegacySettingsXml(it, PreferenceSnapshot.EMPTY.toEditor().apply { this[StringPreferenceKey("darkMode")] = "ON" }.snapshot()) { true }
        }

        BackupAccountVault.mergeIntoStagedXml(xml, accounts) { true }

        val merged = xml.inputStream().use { readLegacySettings(it) }
        assertEquals("ON", merged.strings["darkMode"])
        assertEquals("SAPISID=fixture", merged.strings["innerTubeCookie"])
        val fresh = File(dir, "fresh.xml")
        BackupAccountVault.mergeIntoStagedXml(fresh, accounts) { true }
        assertTrue(fresh.inputStream().use { readLegacySettings(it) }.strings.containsKey("accountName"))
        dir.deleteRecursively()
    }
}
