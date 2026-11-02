package moe.rukamori.archivetune.shared.data.stream.hls

import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.bytesource.toByteSourceAccount
import moe.rukamori.archivetune.shared.data.stream.NativeCiphertextOpener
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.data.stream.NativePreparation
import moe.rukamori.archivetune.shared.data.stream.accountProviderId
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.platform.ContentDigest
import moe.rukamori.archivetune.shared.domain.repository.NativeAudioRecipe
import moe.rukamori.archivetune.shared.domain.source.StreamUrlExpiry

/**
 * Turns a non-DRM HLS media playlist into a plaintext view that a native engine reads through its lease.
 *
 * Segments, init sections and key documents are opened through the host's [NativeCiphertextOpener], which must return
 * range-readable sources for the segment URLs and whole reads for the playlist and key documents. The view's lease
 * covers the playlist origin and every resource origin the playlist names, so the engine cannot reach another host
 * through this stream. Nothing is staged on disk and no progressive URL is substituted.
 *
 * What is refused stays refused: variant playlists, live or unbounded playlists, `SAMPLE-AES` and non-identity key
 * formats (Apple FairPlay, PlayReady, Widevine-class). Those need a platform content-decryption module or a licence
 * exchange this host does not have.
 */
internal object HlsSegmentedPreparation {
    /**
     * Prepares an Apple Music web-playback recipe. Its playlist text was fetched at resolve time. Apple's `ctrp`
     * assets declare `KEYFORMAT="com.apple.streamingkeydelivery"`, which the parser refuses as FairPlay, so the
     * recipe keeps its honest refusal unless the playlist is a genuinely non-DRM AES-128 media playlist. The
     * `licenseUrl` is a FairPlay key server and is never contacted by this host.
     */
    suspend fun prepareApple(
        recipe: NativeAudioRecipe.AppleHls,
        opener: NativeCiphertextOpener,
        admission: AccountAdmission?,
    ): NativePreparation =
        preparePlaylist(
            playlistUrl = recipe.playlistUrl,
            playlistText = recipe.playlistText,
            accountScope = recipe.accountScope,
            providerId = recipe.source.accountProviderId(),
            admission = admission,
            opener = opener,
        )

    /**
     * Prepares a segmented recipe such as a QQ Music manifest: a media playlist read under the recipe's account scope.
     * The playlist document is fetched when the resolver did not supply it.
     */
    suspend fun prepare(
        recipe: NativeAudioRecipe.SegmentedHls,
        opener: NativeCiphertextOpener,
        admission: AccountAdmission?,
    ): NativePreparation =
        preparePlaylist(
            playlistUrl = recipe.playlistUrl,
            playlistText = recipe.playlistText,
            accountScope = recipe.accountScope,
            providerId = recipe.source.accountProviderId(),
            admission = admission,
            opener = opener,
        )

    private suspend fun preparePlaylist(
        playlistUrl: String,
        playlistText: String?,
        accountScope: String,
        providerId: String?,
        admission: AccountAdmission?,
        opener: NativeCiphertextOpener,
    ): NativePreparation {
        val playlistOrigin =
            StreamOrigin.parse(playlistUrl)
                ?: return NativePreparation.Refused("the HLS playlist is not an HTTP(S) resource")
        // A stalled transport throws, which the caller reports as a failure, not a recipe refusal.
        val text =
            playlistText ?: run {
                val bytes =
                    opener.openWhole(playlistUrl, accountScope, MAX_PLAYLIST_DOCUMENT_BYTES)
                        ?: return NativePreparation.Refused("the HLS playlist is unavailable")
                bytes.decodeToString()
            }
        val playlist =
            try {
                HlsMediaPlaylistParser.parse(playlistUrl, text)
            } catch (refusal: HlsPlaylistException) {
                return NativePreparation.Refused(refusal.message ?: "the HLS playlist was rejected")
            } catch (malformed: ByteSourceException) {
                return NativePreparation.Refused(malformed.message ?: "the HLS playlist was rejected")
            }
        // The view's lease covers the playlist origin and every resource origin it names: segments, init sections
        // and key documents. A key URI that does not resolve to HTTP(S) can never be fetched, so it refuses now.
        val keyUrls =
            playlist.keys.map { tag ->
                resolveHlsUri(playlistUrl, tag.keyUri)
                    ?: return NativePreparation.Refused("an HLS key is not an HTTP(S) resource")
            }
        val resourceUrls =
            playlist.segments.map { it.uri } + playlist.initMaps.map { it.uri } + keyUrls + playlistUrl
        val origins = resourceUrls.mapTo(LinkedHashSet()) { StreamOrigin.parse(it) ?: return NativePreparation.Refused("an HLS resource is not HTTP(S)") }
        if (origins.size > MAX_PERMITTED_ORIGINS) {
            return NativePreparation.Refused("the HLS playlist spans more origins than the lease bound")
        }
        // Every resource's own authorization window applies; the view adopts the earliest stated expiry.
        var expiresAtEpochMs: Long? = null
        for (url in resourceUrls) {
            StreamUrlExpiry.fromUrl(url)?.let { resourceExpiry ->
                expiresAtEpochMs = expiresAtEpochMs?.let { minOf(it, resourceExpiry) } ?: resourceExpiry
            }
        }
        val lease =
            ByteSourceLease(
                account =
                    admission?.toByteSourceAccount()
                        ?: ByteSourceAccount(provider = providerId ?: PLAYBACK_PROVIDER, accountId = null),
                primaryOrigin = playlistOrigin,
                permittedOrigins = origins,
                // The digest keeps signed playlist and segment URLs out of host cache keys and logs.
                cacheKey = "hls:" + ContentDigest.sha256(playlistUrl.encodeToByteArray()),
                expiresAtEpochMs = expiresAtEpochMs,
                lengthBytes = null,
            )
        val view = HlsSegmentedSource(lease, playlistUrl, text, accountScope, opener)
        try {
            view.indexOpeningSegments()
        } catch (refusal: HlsSegmentException) {
            return NativePreparation.Refused(refusal.message ?: "the HLS stream was rejected")
        } catch (refusal: HlsPlaylistException) {
            return NativePreparation.Refused(refusal.message ?: "the HLS playlist was rejected")
        }
        return NativePreparation.Ready(
            NativePlaintext(
                source = view,
                mimeType = view.sniffedMimeType ?: UNKNOWN_MIME,
                codec = UNKNOWN_CODEC,
                // The total length is unknown until every segment is indexed, so the engine must not assume one.
                length = null,
                expiresAtEpochMs = lease.expiresAtEpochMs,
            ),
        )
    }

    private const val MAX_PLAYLIST_DOCUMENT_BYTES = 1 shl 20
    private const val MAX_PERMITTED_ORIGINS = 256
    private const val PLAYBACK_PROVIDER = "playback"
    private const val UNKNOWN_MIME = "application/octet-stream"
    private const val UNKNOWN_CODEC = ""
}
