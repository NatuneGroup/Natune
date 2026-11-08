package moe.rukamori.archivetune.shared.domain.settings

/** Null is the install-driven pool default; a Boolean records an explicit user choice. */
data class ModuleAccountSettings(
    val useArchivePool: Boolean? = null,
    val useOwnAccount: Boolean = true,
    val ownAccountFirst: Boolean = true,
) {
    fun poolEnabled(poolInstalled: Boolean): Boolean = poolInstalled && useArchivePool != false

    fun changed(toggle: ModuleAccountToggle, value: Boolean): ModuleAccountSettings = when (toggle) {
        ModuleAccountToggle.USE_POOL -> copy(useArchivePool = value)
        ModuleAccountToggle.USE_OWN_ACCOUNT -> copy(useOwnAccount = value)
        ModuleAccountToggle.OWN_ACCOUNT_FIRST -> copy(ownAccountFirst = value)
    }

    fun order(poolInstalled: Boolean, ownAvailable: Boolean, poolAvailable: Boolean,
        publicInstance: Boolean = false): List<ModuleAccountLane> {
        val personal = if (publicInstance) ModuleAccountLane.INSTANCE else ModuleAccountLane.OWN
        val usePersonal = ownAvailable && (publicInstance || useOwnAccount)
        val usePool = poolEnabled(poolInstalled) && poolAvailable
        val lanes = if (publicInstance || ownAccountFirst) listOf(personal, ModuleAccountLane.POOL)
            else listOf(ModuleAccountLane.POOL, personal)
        return lanes.filter { if (it == ModuleAccountLane.POOL) usePool else usePersonal }
    }
}

enum class ModuleAccountToggle { USE_POOL, USE_OWN_ACCOUNT, OWN_ACCOUNT_FIRST }
enum class ModuleAccountLane { OWN, INSTANCE, POOL }
