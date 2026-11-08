package moe.rukamori.archivetune.modules

import moe.rukamori.archivetune.shared.domain.settings.ModuleAccountLane
import org.json.JSONArray
import org.json.JSONObject

internal const val ARCHIVE_POOL_MODULE_ID = "app.natune.provider.archivepool"
internal val ArchivePoolModuleServices = setOf("tidal", "qobuz")

internal fun modulePoolService(module: InstalledModule): String? =
    module.manifest.id.removePrefix("app.natune.provider.").takeIf { it in ArchivePoolModuleServices }

internal fun ModuleRegistry.installedPool(): InstalledModule? = modules.firstOrNull { it.manifest.id == ARCHIVE_POOL_MODULE_ID && it.availableForPlayback() }
    ?: modules.firstOrNull { it.manifest.id == ARCHIVE_POOL_MODULE_ID }

internal fun moduleUsesPublicInstance(module: InstalledModule): Boolean =
    parseJsonValue(module.configuration["mode"] ?: "null") == "instance"

internal fun moduleOwnAccountConfigured(module: InstalledModule): Boolean = module.copy(enabled = true,
    configuration = module.configuration + ("mode" to JSONObject.quote("account"))).availableForPlayback()

internal data class ModuleExecution(val provider: InstalledModule, val configuration: Map<String, String>, val lane: ModuleAccountLane)

/** The standalone pool cannot bypass a source's disabled pool switch or run its fallback twice. */
internal fun moduleExecutionRoutes(registry: ModuleRegistry, module: InstalledModule): List<ModuleExecution> {
    if (!module.enabled || !module.manifest.providesAudio) return emptyList()
    if (module.manifest.id == ARCHIVE_POOL_MODULE_ID) {
        if (!module.availableForPlayback()) return emptyList()
        val attached = registry.modules.mapNotNull(::modulePoolService).toSet()
        val allowed = ArchivePoolModuleServices - attached
        if (allowed.isEmpty()) return emptyList()
        return listOf(ModuleExecution(module, module.configuration +
            ("services" to JSONArray(allowed.toList()).toString()), ModuleAccountLane.POOL))
    }
    val service = modulePoolService(module) ?: return if (module.availableForPlayback())
        listOf(ModuleExecution(module, module.configuration, ModuleAccountLane.OWN)) else emptyList()
    val pool = registry.installedPool()
    val publicInstance = moduleUsesPublicInstance(module)
    return module.accountSettings.order(pool != null, module.availableForPlayback(), pool?.availableForPlayback() == true,
        publicInstance).map { lane ->
        if (lane == ModuleAccountLane.POOL) {
            val provider = requireNotNull(pool)
            ModuleExecution(provider, provider.configuration + mapOf("service" to JSONObject.quote(service),
                "quality" to (module.configuration["quality"] ?: "null")), lane)
        } else ModuleExecution(module, module.configuration, lane)
    }
}
