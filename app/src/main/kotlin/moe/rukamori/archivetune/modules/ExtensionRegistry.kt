package moe.rukamori.archivetune.modules

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.domain.provider.CompiledProviderDescriptor
import moe.rukamori.archivetune.shared.domain.provider.ProviderPurpose
import moe.rukamori.archivetune.spotify.SpotifyProviderPreferences
import moe.rukamori.archivetune.spotify.SpotifyProviderRegistrations
import moe.rukamori.archivetune.utils.dataStore
import javax.inject.Inject
import javax.inject.Singleton

/** Android native contract. Neither shared code nor the QuickJS runtime loads native extensions. */
interface NativeExtension {
    val id: String
    val kind: ModuleKind
    val displayName: String
    val enabled: Flow<Boolean>
    val settingsRoute: String
    val priority: Flow<Int>
}

data class ExtensionRegistration(
    val id: String,
    val kind: ModuleKind,
    val displayName: String,
    val enabled: Boolean,
    val priority: Int,
    val native: Boolean,
    val settingsRoute: String?,
)

@Singleton
class ExtensionRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modules: ModuleRepository,
) {
    private inner class BuiltIn(
        descriptor: CompiledProviderDescriptor,
    ) : NativeExtension {
        override val id: String = descriptor.id.stableId
        override val kind: ModuleKind = if (ProviderPurpose.CATALOG in descriptor.purposes) ModuleKind.METADATA else ModuleKind.PLAYBACK
        override val displayName: String = context.getString(SpotifyProviderRegistrations.nameResource(descriptor.id))
        override val settingsRoute: String = SpotifyProviderRegistrations.settingsPath(descriptor.settingsRoute)
        override val enabled = context.dataStore.data.map { SpotifyProviderPreferences.enabled(id, it) }.distinctUntilChanged()
        override val priority = context.dataStore.data.map { SpotifyProviderPreferences.priority(id, it) }.distinctUntilChanged()
    }

    val nativeExtensions: List<NativeExtension> = SpotifyProviderRegistrations.descriptors.map { BuiltIn(it) }

    val state: Flow<List<ExtensionRegistration>> = combine(modules.state, context.dataStore.data) { registry, prefs ->
        nativeExtensions.map { native ->
            ExtensionRegistration(native.id, native.kind, native.displayName, SpotifyProviderPreferences.enabled(native.id, prefs),
                SpotifyProviderPreferences.priority(native.id, prefs), true, native.settingsRoute)
        } + registry?.modules.orEmpty().mapIndexed { index, module ->
            ExtensionRegistration(module.id, module.manifest.kind, module.metadata?.name ?: module.manifest.displayName.ifBlank { module.manifest.name },
                module.enabled, index + 1, false, null)
        }
    }

    suspend fun initialize() {
        context.dataStore.edit { SpotifyProviderPreferences.migrate(it) }
        modules.snapshot()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val native = nativeExtensions.firstOrNull { it.id == id }
        if (native == null) modules.setModuleEnabled(id, enabled)
        else context.dataStore.edit { it[SpotifyProviderPreferences.keys.getValue(id)] = enabled }
    }

    /** Native Spotify playback shares its existing SPOTIFY source slot and engine preference. */
    suspend fun moveNative(id: String, delta: Int) {
        context.dataStore.edit { SpotifyProviderPreferences.move(id, delta, it) }
    }
}
