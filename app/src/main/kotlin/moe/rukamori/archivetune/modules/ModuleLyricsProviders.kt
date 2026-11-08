package moe.rukamori.archivetune.modules

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.lyrics.LyricsProvider
import javax.inject.Inject
import javax.inject.Singleton

val PreferLyricsExtensionsKey = booleanPreferencesKey("preferLyricsExtensions")

/** Adapts installed Natune lyrics modules to the existing Android provider chain. */
@Singleton
class ModuleLyricsProviders @Inject constructor(private val repository: ModuleRepository) {
    suspend fun snapshot(): String = repository.snapshot().revision

    val providers: List<LyricsProvider>
        get() = repository.state.value?.modules.orEmpty()
            .filter { it.manifest.kind == ModuleKind.LYRICS }
            .map { module -> Provider(module) }

    private inner class Provider(private val module: InstalledModule) : LyricsProvider {
        // The installation id keeps two providers with identical publisher names distinct.
        override val name = "${module.metadata?.name ?: module.manifest.displayName.ifBlank { module.manifest.name }} (${module.id})"

        override fun isEnabled(context: Context): Boolean = repository.state.value?.let { registry ->
            registry.enabled && registry.modules.any { it.id == module.id && it.enabled && it.revision == module.revision }
        } == true

        override suspend fun getLyrics(id: String, title: String, artist: String, album: String?, duration: Int): Result<String> =
            getLyrics(id, title, artist, album, duration, null)

        override suspend fun getLyrics(id: String, title: String, artist: String, album: String?, duration: Int, isrc: String?): Result<String> = try {
            if (!isEnabledForRequest()) Result.failure(NoSuchElementException("Lyrics extension disabled")) else {
                ModuleRepository.validateConfiguration(module.manifest, module.configuration)
                val value = withTimeoutOrNull(15_000) {
                    repository.runtime.lyrics(module.id, module.source, module.origin,
                        ModuleTrack(id, title, listOf(artist), album, duration.takeIf { it > 0 }, null, isrc), module.configuration)
                }
                if (value == null || !isEnabledForRequest()) Result.failure(NoSuchElementException("No extension lyrics"))
                else Result.success(value.text)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }

        private suspend fun isEnabledForRequest(): Boolean {
            val registry = repository.snapshot()
            return registry.enabled && registry.modules.any { it.id == module.id && it.enabled && it.revision == module.revision }
        }
    }
}
