package moe.rukamori.archivetune.shared.domain.repository

import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.source.*

/** Catalog metadata, not authoritative queue state. Retain the complete provider payload for adapters. */
data class ProviderTrack(val item: ContentItem, val artists: List<String>, val album: String?,
    val isrc: String?, val rawJson: String) {
    fun candidate() = TrackMatching.Candidate(item.ref.id, item.title, artists, album, item.durationMs)
}
enum class AudioPurpose { PLAYBACK, DOWNLOAD }
data class AudioResolveRequest(
    val mediaId: String,
    val target: TrackMatching.Target,
    val directIds: Map<AudioSourceType, String> = emptyMap(),
    val isrc: String? = null,
    val qualities: Map<AudioSourceType, String> = emptyMap(),
    val purpose: AudioPurpose = AudioPurpose.PLAYBACK,
)

/** Only Progressive is acceptable to the existing progressive downloader/ordinary Remote locator. */
sealed interface PreparedAudio {
    val source: AudioSourceType
    data class Progressive(val stream: DirectStream) : PreparedAudio {
        override val source get() = stream.source
    }
    data class DeezerEncrypted(val stream: DirectStream, val trackId: String, val keyScope: String) : PreparedAudio {
        override val source get() = stream.source
    }
    /** Evidence plus a required native recipe; never an ordinary progressive engine locator. */
    data class Native(val stream: DirectStream, val recipe: NativeAudioRecipe) : PreparedAudio {
        override val source get() = stream.source
        init {
            require(stream.source == recipe.source)
            // The stream and its recipe name one account, so admission and cookies can never disagree about it.
            require(stream.accountScope == null || stream.accountScope == recipe.accountScope) {
                "a native stream and its recipe must name the same account"
            }
        }
    }
    /**
     * The native owner must parse/prepare this using the retained tidal-dash route or remuxer.
     * [accountScope] is the scope the manifest was resolved under: the personal or pool scope when a bearer token
     * resolved it, or the anonymous scope for the public instance. Null means the scope was not recorded, and the
     * native route refuses it rather than read the manifest as anonymous.
     */
    data class TidalManifest(val manifestText: String, val track: ProviderTrack,
        val quality: String, val trustedDirectId: Boolean, val accountScope: String? = null) : PreparedAudio {
        override val source get() = AudioSourceType.TIDAL
        init {
            require(accountScope == null || accountScope.isNotBlank()) {
                "a manifest account scope must not be blank"
            }
        }
    }
}

/**
 * The playback locator for a Deezer prepared stream. The track id is part of the locator because the chunk key derives
 * from it, so the engine host can decrypt without a side channel. This is never a [PlaybackLocator.Remote].
 */
fun PreparedAudio.DeezerEncrypted.toPlaybackLocator(): PlaybackLocator.DeezerEncrypted =
    PlaybackLocator.DeezerEncrypted(trackId = trackId, url = stream.uri, headers = stream.headers)

/** Short-lived provider material in memory, not another vault or credential owner. */
class AudioSecret(private val value: String) {
    init { require(value.isNotBlank()) }
    fun reveal(): String = value
    override fun toString(): String = "AudioSecret(REDACTED)"
}

sealed interface NativeAudioRecipe {
    val source: AudioSourceType
    val accountScope: String
    data class AppleHls(val playlistUrl: String, val playlistText: String, val licenseUrl: String,
        val flavor: String, override val accountScope: String) : NativeAudioRecipe {
        override val source get() = AudioSourceType.APPLE
    }
    data class AmazonCenc(val url: String, val key: AudioSecret, override val accountScope: String) : NativeAudioRecipe {
        override val source get() = AudioSourceType.AMAZON
    }
    /**
     * A QQ Music protected container. [eKey] is null for a footer-less QMC1 container, which the static cipher opens
     * without a service key. A container whose footer needs a key is refused by the preparer when no key is present.
     */
    data class QqEncrypted(val url: String, val eKey: AudioSecret?, override val accountScope: String) : NativeAudioRecipe {
        override val source get() = AudioSourceType.QQ
    }
}
interface AudioProviderRepository {
    val source: AudioSourceType
    suspend fun resolve(request: AudioResolveRequest): PreparedAudio?
}
/** Actionable auth failures stop the terminal YouTube tier chain rather than bypass login. */
class TerminalPlaybackAuthentication : IllegalStateException("Playback authentication requires user action")
class UnsupportedContentOperation : UnsupportedOperationException("Unsupported provider content operation")
