package moe.rukamori.archivetune.modules

/** Keep installation identity, secrets and endpoint while selecting the legacy connection mode. */
internal fun unifiedProviderConfiguration(previous: InstalledModule, manifest: ModuleManifest): Map<String, String> =
    manifest.settings.associate { field ->
        field.key to when (field.key) {
            "mode" -> if (previous.manifest.id.endsWith(".instance")) "\"instance\"" else "\"account\""
            else -> previous.configuration[field.key]?.takeIf { field.acceptsValue(it) } ?: field.defaultJson
        }
    }
