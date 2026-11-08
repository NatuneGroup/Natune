package moe.rukamori.archivetune.modules

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedProviderConfigurationTest {
    private fun field(key: String, type: ModuleSettingType = ModuleSettingType.TEXT, value: String = "null") =
        ModuleSetting(key, key, "", type, value, false, key == "accessToken", emptyList())

    private fun manifest(id: String) = ModuleManifest(id, "Provider", "1.2.0", "", "", emptyList(),
        listOf(field("mode"), field("instanceUrl"), field("accessToken", ModuleSettingType.PASSWORD),
            field("quality", ModuleSettingType.NUMBER, "6")), listOf("searchTracks", "getTrackStreamUrl"))

    @Test fun legacyInstanceRetainsEndpointAndQualityWithoutRequiringAccountCredentials() {
        for (service in listOf("tidal", "qobuz")) {
            val old = manifest("app.natune.provider.$service.instance")
            val installed = InstalledModule("installation", old, "", "", false,
                mapOf("instanceUrl" to "\"https://instance.example/custom\"", "quality" to "27"), "revision")
            val values = unifiedProviderConfiguration(installed, manifest("app.natune.provider.$service"))
            assertEquals("\"instance\"", values["mode"])
            assertEquals(installed.configuration["instanceUrl"], values["instanceUrl"])
            assertEquals("27", values["quality"])
            assertEquals("null", values["accessToken"])
        }
    }

    @Test fun accountUpgradeRetainsPersonalCredentialsAndUsesAccountMode() {
        val old = manifest("app.natune.provider.tidal")
        val installed = InstalledModule("installation", old, "", "", true,
            mapOf("accessToken" to "\"synthetic-fixture-token\""), "revision")
        val values = unifiedProviderConfiguration(installed, old)
        assertEquals("\"account\"", values["mode"])
        assertEquals(installed.configuration["accessToken"], values["accessToken"])
    }
    @Test fun laterUnifiedUpgradeKeepsTheExplicitPublicInstanceMode() {
        val old = manifest("app.natune.provider.tidal")
        val installed = InstalledModule("installation", old, "", "", true,
            mapOf("mode" to "\"instance\"", "instanceUrl" to "\"https://instance.example\""), "revision")
        val values = unifiedProviderConfiguration(installed, old)
        assertEquals("\"instance\"", values["mode"])
        assertEquals(installed.configuration["instanceUrl"], values["instanceUrl"])
    }

}
