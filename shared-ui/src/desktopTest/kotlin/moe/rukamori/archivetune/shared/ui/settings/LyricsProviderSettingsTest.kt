package moe.rukamori.archivetune.shared.ui.settings

import java.nio.file.Files
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixDiagnostics
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class LyricsProviderSettingsTest {
    @Test fun actualEndpointDialogUsesCommittedConfigurationAndShowsPublishedStatuses() = runBlocking {
        val directory = Files.createTempDirectory("m6-pax-dialog-")
        // PaxsenixApiKeyKey is a routed credential: plain disk persistence rejects it, so the test
        // owns a real vault adapter like the shipped host does.
        val store = VaultTestOwner.open(directory.resolve("settings.bin"), TestSettingsVault())
        try {
            store.edit { this[PaxsenixEndpointKey] = "https://example.invalid/"; this[PaxsenixApiKeyKey] = "test-only-key" }
            val controller = SettingsController(store, store.snapshot(), false)
            var requests = 0
            runComposeUiTest {
                setContent { MaterialTheme {
                    LyricsProviderSettings(controller, readStats = { error("Not a stats request") },
                        checkEndpoints = { preferences ->
                            assertEquals("https://example.invalid/", preferences[PaxsenixEndpointKey])
                            assertEquals("test-only-key", preferences[PaxsenixApiKeyKey])
                            requests++
                            listOf(PaxsenixDiagnostics.PathCheck("Fake retired provider", "test", PaxsenixDiagnostics.PathStatus.RETIRED, 403))
                        }, onFailure = { error("Unexpected edit failure") }, scrollTo = "paxsenix_check_endpoints")
                } }
                onNodeWithTag("setting:paxsenix_check_endpoints").performClick()
                onNodeWithText("Fake retired provider").assertExists()
                onNodeWithText("Retired").assertExists()
                onNodeWithText("Done").performClick()
                assertEquals(1, requests)
            }
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
