package moe.rukamori.archivetune.modules

/** Read-only readiness for providers with static account or instance settings. */
internal fun InstalledModule.availableForPlayback(): Boolean {
    if (!enabled || !manifest.providesAudio) return false
    if (manifest.settings.any { !it.acceptsValue(configuration[it.key] ?: it.defaultJson) }) return false
    fun text(key: String): String = (parseJsonValue(configuration[key] ?: "null") as? String)?.trim().orEmpty()
    return when (manifest.id) {
        "app.natune.provider.tidal", "app.natune.provider.qobuz" -> when (text("mode").ifEmpty { "account" }) {
            "instance" -> runCatching {
                val uri = java.net.URI(text("instanceUrl"))
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
            }.getOrDefault(false)
            "account" -> if (manifest.id.endsWith("tidal")) {
                text("accessToken").isNotEmpty() || (text("refreshToken").isNotEmpty() && text("clientId").isNotEmpty())
            } else {
                text("appId").isNotEmpty() && text("appSecret").isNotEmpty() &&
                    (text("userAuthToken").isNotEmpty() || (text("email").isNotEmpty() && text("password").isNotEmpty()))
            }
            else -> false
        }
        else -> true
    }
}
