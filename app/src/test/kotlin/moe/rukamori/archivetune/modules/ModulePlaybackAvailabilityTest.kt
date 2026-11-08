package moe.rukamori.archivetune.modules

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModulePlaybackAvailabilityTest {
    private fun installed(service: String, enabled: Boolean = true, settings: Map<String, String>) =
        InstalledModule("installation", ModuleManifest("app.natune.provider.$service", service, "1.2.0", "", "",
            emptyList(), emptyList(), listOf("searchTracks", "getTrackStreamUrl"), types = listOf("audio_provider")),
            "", "", enabled, settings.mapValues { JSONObject.quote(it.value) }, "revision")

    @Test fun disabledAndUnconfiguredModulesCannotAppearAsPlaybackSources() {
        assertFalse(installed("tidal", settings = emptyMap()).availableForPlayback())
        assertFalse(installed("qobuz", settings = emptyMap()).availableForPlayback())
        assertFalse(installed("tidal", false, mapOf("accessToken" to "synthetic")).availableForPlayback())
    }

    @Test fun instanceModeRequiresHttpsEndpointAndDoesNotRequireAccount() {
        assertTrue(installed("tidal", settings = mapOf("mode" to "instance", "instanceUrl" to "https://instance.example/custom")).availableForPlayback())
        assertFalse(installed("qobuz", settings = mapOf("mode" to "instance", "instanceUrl" to "http://instance.example")).availableForPlayback())
        assertFalse(installed("tidal", settings = mapOf("mode" to "instance", "accessToken" to "synthetic")).availableForPlayback())
    }

    @Test fun accountModeRequiresTheSelectedServicesAccount() {
        assertTrue(installed("tidal", settings = mapOf("accessToken" to "synthetic")).availableForPlayback())
        assertFalse(installed("qobuz", settings = mapOf("userAuthToken" to "synthetic")).availableForPlayback())
        assertTrue(installed("qobuz", settings = mapOf("appId" to "123", "appSecret" to "synthetic", "userAuthToken" to "synthetic")).availableForPlayback())
    }
}
