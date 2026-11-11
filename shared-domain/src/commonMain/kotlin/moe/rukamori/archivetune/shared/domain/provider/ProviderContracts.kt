package moe.rukamori.archivetune.shared.domain.provider

import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.SpotifyProviderIds

/** Compiled implementations only. Profiles can configure these IDs, never register executable code. */
enum class CompiledProviderId(val stableId: String, val audioSource: AudioSourceType?) {
    TIDAL("tidal", AudioSourceType.TIDAL),
    QOBUZ("qobuz", AudioSourceType.QOBUZ),
    QOBUZ_BACKUP("qobuz_backup", AudioSourceType.QOBUZ_BACKUP),
    DEEZER("deezer", AudioSourceType.DEEZER),
    APPLE("apple", AudioSourceType.APPLE),
    AMAZON("amazon", AudioSourceType.AMAZON),
    QQ("qq", AudioSourceType.QQ),
    JIOSAAVN("jiosaavn", AudioSourceType.JIOSAAVN),
    SPOTIFY_CATALOG(SpotifyProviderIds.CATALOG, null),
    SPOTIFY_ALTERNATE(SpotifyProviderIds.ALTERNATE, AudioSourceType.SPOTIFY),
    SPOTIFY_LIBRESPOT(SpotifyProviderIds.LIBRESPOT, AudioSourceType.SPOTIFY),
    YOUTUBE("youtube", AudioSourceType.YOUTUBE),
    LRCLIB("lrclib", null),
}

enum class ProviderPurpose { CATALOG, PLAYBACK, DOWNLOAD, LYRICS }
enum class ProviderPlatform { ANDROID, DESKTOP, IOS }
enum class ProviderSettingsDestination { AUDIO_SOURCES, SPOTIFY_CATALOG, SPOTIFY_STREAMING, LYRICS }

data class ProviderSettingsRoute(val destination: ProviderSettingsDestination, val anchor: String? = null)

/** Composition supplies capabilities for its platform; a descriptor never loads an implementation. */
data class CompiledProviderDescriptor(
    val id: CompiledProviderId,
    val displayResourceKey: String,
    val purposes: Set<ProviderPurpose>,
    val platforms: Set<ProviderPlatform>,
    val settingsRoute: ProviderSettingsRoute,
)

enum class ProviderProfileMode { OWN_ACCOUNT, INSTANCE }

/** DEFAULT leaves credential sequencing with the current provider's account/Pool policy. */
enum class CredentialLane { DEFAULT, PERSONAL, INSTANCE, PLAYBACK_POOL, SPOTIFY_CATALOG, SPOTIFY_STREAMING }

/** An opaque vault reference, never a password, cookie, bearer token or leased Pool credential. */
data class ProviderCredentialRef(val value: String) {
    init { require(value.isNotBlank()) }
}

/** Independent profiles retain distinct accounts/endpoints even when they share one audio source. */
data class ProviderProfile(
    val id: String,
    val providerId: CompiledProviderId,
    val mode: ProviderProfileMode,
    val enabled: Boolean,
    val endpoint: String? = null,
    val credentialRef: ProviderCredentialRef? = null,
    /** Keep the provider's existing quality name; mapping imported choices belongs to migration. */
    val quality: String? = null,
    val credentialOrder: List<CredentialLane> = listOf(CredentialLane.DEFAULT),
) {
    init { require(id.isNotBlank()) }
}

/** Secure platform/data owners implement storage; common consumers receive plain snapshots. */
interface ProviderProfilePort {
    suspend fun profiles(): List<ProviderProfile>
}
