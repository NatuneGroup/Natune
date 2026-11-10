package moe.rukamori.archivetune.modules

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine
import moe.rukamori.archivetune.spotify.SpotifyStreamEngineKey
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
        override val id: String,
        override val kind: ModuleKind,
        name: Int,
        override val settingsRoute: String,
    ) : NativeExtension {
        override val displayName: String = context.getString(name)
        override val enabled = context.dataStore.data.map { NativeExtensionPolicy.enabled(id, it) }.distinctUntilChanged()
        override val priority = context.dataStore.data.map { NativeExtensionPolicy.priority(id, it) }.distinctUntilChanged()
    }

    val nativeExtensions: List<NativeExtension> = listOf(
        BuiltIn(NativeExtensionIds.SPOTIFY_METADATA, ModuleKind.METADATA, R.string.extensions_spotify_metadata, "settings/spotify?scrollTo=extensions_spotify_metadata"),
        BuiltIn(NativeExtensionIds.SPOTIFY_ALTERNATE, ModuleKind.PLAYBACK, R.string.extensions_spotify_alternate, "settings/spotify_streaming?scrollTo=extension_alternate"),
        BuiltIn(NativeExtensionIds.SPOTIFY_OFFICIAL, ModuleKind.PLAYBACK, R.string.extensions_spotify_official, "settings/spotify_streaming?scrollTo=extension_official"),
    )

    val state: Flow<List<ExtensionRegistration>> = combine(modules.state, context.dataStore.data) { registry, prefs ->
        nativeExtensions.map { native ->
            ExtensionRegistration(native.id, native.kind, native.displayName, NativeExtensionPolicy.enabled(native.id, prefs),
                NativeExtensionPolicy.priority(native.id, prefs), true, native.settingsRoute)
        } + registry?.modules.orEmpty().mapIndexed { index, module ->
            ExtensionRegistration(module.id, module.manifest.kind, module.metadata?.name ?: module.manifest.displayName.ifBlank { module.manifest.name },
                module.enabled, index + 1, false, null)
        }
    }

    suspend fun initialize() {
        context.dataStore.edit { NativeExtensionPolicy.migrate(it) }
        modules.snapshot()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val native = nativeExtensions.firstOrNull { it.id == id }
        if (native == null) modules.setModuleEnabled(id, enabled)
        else context.dataStore.edit { it[NativeExtensionPolicy.keys.getValue(id)] = enabled }
    }

    /** Native Spotify playback shares its existing SPOTIFY source slot and engine preference. */
    suspend fun moveNative(id: String, delta: Int) {
        if (id !in listOf(NativeExtensionIds.SPOTIFY_ALTERNATE, NativeExtensionIds.SPOTIFY_OFFICIAL) || delta !in listOf(-1, 1)) return
        context.dataStore.edit { prefs ->
            val current = NativeExtensionPolicy.priority(id, prefs)
            if (current + delta !in 1..2) return@edit
            val officialFirst = (id == NativeExtensionIds.SPOTIFY_OFFICIAL) == (delta < 0)
            prefs[SpotifyStreamEngineKey] = if (officialFirst) SpotifyStreamEngine.LIBRESPOT.name else SpotifyStreamEngine.UNOFFICIAL.name
        }
    }
}
