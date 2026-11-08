package moe.rukamori.archivetune.modules

import moe.rukamori.archivetune.shared.domain.settings.ModuleAccountLane
import moe.rukamori.archivetune.shared.domain.settings.ModuleAccountSettings
import moe.rukamori.archivetune.shared.domain.settings.ModuleAccountToggle
import org.junit.Assert.*
import org.junit.Test

class ModuleAccountRoutesTest {
    private fun source(service: String = "tidal", own: Boolean = true, settings: ModuleAccountSettings = ModuleAccountSettings()) =
        InstalledModule(service, ModuleManifest("app.natune.provider.$service", service, "1.2.0", "", "", emptyList(), emptyList(),
            listOf("searchTracks", "getTrackStreamUrl"), types = listOf("audio_provider")), "", "", true,
            if (!own) emptyMap() else if (service == "tidal") mapOf("accessToken" to "\"synthetic\"", "quality" to "\"LOW\"")
            else mapOf("userAuthToken" to "\"synthetic\"", "appId" to "\"12345\"", "appSecret" to "\"synthetic\"", "quality" to "27"), "revision", accountSettings = settings)

    private fun pool(enabled: Boolean = true) = InstalledModule("pool", ModuleManifest(ARCHIVE_POOL_MODULE_ID, "Pool", "1.0.0", "", "",
        emptyList(), emptyList(), listOf("searchTracks", "getTrackStreamUrl"), types = listOf("audio_provider")), "", "", enabled,
        mapOf("readKey" to "\"synthetic\"", "quality" to "\"lossless\""), "pool-revision")

    private fun registry(vararg modules: InstalledModule) = ModuleRegistry(enabled = true, modules = modules.toList())

    @Test fun accountFirstAndPoolFirstExecuteTheRequestedOrder() {
        for (service in listOf("tidal", "qobuz")) {
            val ownFirst = source(service)
            assertEquals(listOf(ModuleAccountLane.OWN, ModuleAccountLane.POOL), moduleExecutionRoutes(registry(ownFirst, pool()), ownFirst).map { it.lane })
            val poolFirst = source(service, settings = ModuleAccountSettings(ownAccountFirst = false))
            assertEquals(listOf(ModuleAccountLane.POOL, ModuleAccountLane.OWN), moduleExecutionRoutes(registry(poolFirst, pool()), poolFirst).map { it.lane })
        }
    }

    @Test fun poolInstallationAutomaticallyEnablesExistingAndLaterSources() {
        val existing = source()
        assertEquals(listOf(ModuleAccountLane.OWN), moduleExecutionRoutes(registry(existing), existing).map { it.lane })
        assertTrue(moduleExecutionRoutes(registry(existing, pool()), existing).any { it.lane == ModuleAccountLane.POOL })
        val later = source(own = false)
        assertEquals(listOf(ModuleAccountLane.POOL), moduleExecutionRoutes(registry(pool(), later), later).map { it.lane })
    }

    @Test fun explicitPoolChoiceSurvivesUninstallAndReinstall() {
        val preferences = ModuleAccountSettings().changed(ModuleAccountToggle.USE_POOL, false)
        val module = source(settings = preferences)
        assertFalse(preferences.poolEnabled(false))
        assertFalse(preferences.poolEnabled(true))
        assertEquals(listOf(ModuleAccountLane.OWN), moduleExecutionRoutes(registry(module, pool()), module).map { it.lane })
    }

    @Test fun ownAccountAndPoolTogglesAreIndependentAndCannotBeBypassedByStandalonePool() {
        val disabled = source(settings = ModuleAccountSettings(useArchivePool = false, useOwnAccount = false))
        assertTrue(moduleExecutionRoutes(registry(disabled, pool()), disabled).isEmpty())
        val standalone = moduleExecutionRoutes(registry(disabled, pool()), pool()).single()
        assertEquals("[\"qobuz\"]", standalone.configuration["services"])
        val pooled = source(settings = ModuleAccountSettings(useOwnAccount = false))
        assertEquals(listOf(ModuleAccountLane.POOL), moduleExecutionRoutes(registry(pooled, pool()), pooled).map { it.lane })
    }

    @Test fun uninstallOrDisableImmediatelyRemovesPoolRoutesWithoutChangingExplicitSettings() {
        val module = source(settings = ModuleAccountSettings(useArchivePool = true))
        assertEquals(listOf(ModuleAccountLane.OWN), moduleExecutionRoutes(registry(module), module).map { it.lane })
        assertEquals(listOf(ModuleAccountLane.OWN), moduleExecutionRoutes(registry(module, pool(false)), module).map { it.lane })
        assertTrue(module.accountSettings.useArchivePool == true)
    }

    @Test fun leasedExecutionIsServiceBoundAndKeepsTheSourcesActualQuality() {
        val module = source()
        val execution = moduleExecutionRoutes(registry(module, pool()), module).last()
        assertEquals("pool", execution.provider.id)
        assertEquals("\"tidal\"", execution.configuration["service"])
        assertEquals("\"LOW\"", execution.configuration["quality"])
    }
}
