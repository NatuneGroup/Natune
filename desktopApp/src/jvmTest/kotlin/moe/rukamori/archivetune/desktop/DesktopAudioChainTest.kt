package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.provider.AudioAttemptResult
import moe.rukamori.archivetune.shared.data.provider.AudioResolution
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.data.provider.YouTubeResolutionPort
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.*
import moe.rukamori.archivetune.shared.ui.host.hostSourceQualities
import moe.rukamori.archivetune.shared.ui.host.toAudioResolveRequest

class DesktopAudioChainTest {
    private val request = AudioResolveRequest("song", TrackMatching.Target("Song", listOf("Artist"), null, 180_000))
    private val expected = listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.QOBUZ_BACKUP,
        AudioSourceType.DEEZER, AudioSourceType.APPLE, AudioSourceType.JIOSAAVN, AudioSourceType.AMAZON,
        AudioSourceType.QQ, AudioSourceType.SPOTIFY, AudioSourceType.YOUTUBE)

    private fun allEnabled(): PreferenceEditor = PreferenceSnapshot.EMPTY.toEditor().apply {
        AudioSourceType.entries.forEach { this[it.enabledPreference()] = true }
        this[SpotifyStreamSpDcKey] = "stream-session"
        this[SpotifyStreamUserIdKey] = "stream-user"
    }

    private fun resolver(seen: MutableList<AudioSourceType>) = OrderedAudioResolver(
        expected.filter { it != AudioSourceType.YOUTUBE }.reversed().map { type ->
            object : AudioProviderRepository {
                override val source = type
                override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
                    seen += type
                    if (type == AudioSourceType.APPLE) {
                        throw AudioProviderUnavailable(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED)
                    }
                    return null
                }
            }
        },
        object : YouTubeResolutionPort {
            override suspend fun primary(request: AudioResolveRequest): PreparedAudio {
                seen += AudioSourceType.YOUTUBE
                return PreparedAudio.Progressive(DirectStream("https://audio.invalid/song", "audio/ogg", "opus",
                    null, "YOUTUBE", AudioSourceType.YOUTUBE, "Song", "Artist", null, 180_000))
            }
            override suspend fun fallback(tier: YouTubeFallbackTier, request: AudioResolveRequest): PreparedAudio? =
                error("primary succeeded")
        },
    )

    @Test fun allTiersFollowAndroidOrderAndPlatformRefusalsContinueToTerminalYouTube() = runTest {
        val seen = mutableListOf<AudioSourceType>()
        val result = assertIs<AudioResolution.Resolved>(resolver(seen).resolve(request,
            desktopSourceSnapshot(allEnabled().snapshot())))
        assertEquals(expected, seen)
        val apple = result.attempts.single { it.source == AudioSourceType.APPLE }
        assertEquals(AudioAttemptResult.UNAVAILABLE, apple.result)
        assertEquals(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED, apple.unavailableReason)
    }

    @Test fun configuredOrderDoesNotMoveYouTubeAheadOfOtherTiers() = runTest {
        val seen = mutableListOf<AudioSourceType>()
        val reordered = expected.reversed()
        val prefs = allEnabled().apply { this[AudioSourceOrderKey] = reordered.joinToString(",") { it.name } }.snapshot()
        resolver(seen).resolve(request, desktopSourceSnapshot(prefs))
        assertEquals(reordered.filter { it != AudioSourceType.YOUTUBE } + AudioSourceType.YOUTUBE, seen)
    }

    @Test fun pinCannotEnableASourceAndRejectedOrDownloadSpotifyIsNotRetried() = runTest {
        val seen = mutableListOf<AudioSourceType>()
        val prefs = allEnabled().apply { this[QobuzEnabledKey] = false }.snapshot()
        resolver(seen).resolve(request.copy(purpose = AudioPurpose.DOWNLOAD), desktopSourceSnapshot(prefs),
            pinned = AudioSourceType.QOBUZ, rejected = setOf(AudioSourceType.AMAZON))
        assertEquals(expected.filter { it !in setOf(AudioSourceType.QOBUZ, AudioSourceType.AMAZON, AudioSourceType.SPOTIFY) }, seen)
        seen.clear()
        resolver(seen).resolve(request, desktopSourceSnapshot(allEnabled().apply {
            this[YouTubeSourceEnabledKey] = false
        }.snapshot()))
        assertFalse(AudioSourceType.YOUTUBE in seen)
    }

    @Test fun defaultsAndStreamingAccountGateNeverFollowCatalogSignIn() {
        assertEquals(setOf(AudioSourceType.TIDAL, AudioSourceType.YOUTUBE), desktopSourceSnapshot(null).enabled)
        val signedOut = PreferenceSnapshot.of(SpotifyStreamEnabledKey to true, SpotifySpDcKey to "catalog-session")
        assertFalse(AudioSourceType.SPOTIFY in desktopSourceSnapshot(signedOut).enabled)
        val ready = allEnabled()
        assertTrue(AudioSourceType.SPOTIFY in desktopSourceSnapshot(ready.snapshot()).enabled)
        ready[SpotifySpDcKey] = ready[SpotifyStreamSpDcKey]
        assertFalse(AudioSourceType.SPOTIFY in desktopSourceSnapshot(ready.snapshot()).enabled)
    }

    @Test fun storedQualityVocabularyAndDirectIdsReachOnlyTheirIssuingSource() {
        for ((stored, numeric) in listOf("FLAC" to "6", "HI_RES" to "7", "MAX" to "27")) {
            assertEquals(numeric, hostSourceQualities(PreferenceSnapshot.of(QobuzAudioQualityKey to stored))[AudioSourceType.QOBUZ])
        }
        val qualities = hostSourceQualities(PreferenceSnapshot.of(AppleMusicQualityKey to "AAC", AmazonAudioQualityKey to "ULTRA_HD"))
        assertEquals("AAC", qualities[AudioSourceType.APPLE])
        assertEquals("ULTRA_HD", qualities[AudioSourceType.AMAZON])
        assertNull(hostSourceQualities(PreferenceSnapshot.of(QobuzAudioQualityKey to "unknown"))[AudioSourceType.QOBUZ])
        for (provider in listOf(ContentProvider.SPOTIFY_CATALOG, ContentProvider.SPOTIFY_STREAMING)) {
            val item = ContentItem(ContentRef(provider.id, ContentKind.SONG, "track"), "Song")
            assertEquals(mapOf(AudioSourceType.SPOTIFY to "track"),
                ContentOccurrence("one", item).toAudioResolveRequest(null).directIds)
        }
        val suggestion = ContentItem(ContentRef(ContentProvider.AMAZON.id, ContentKind.SONG, "itunes-suggestion"), "Song")
        assertTrue(ContentOccurrence("one", suggestion).toAudioResolveRequest(null).directIds.isEmpty())
    }

    @Test fun amazonMetadataPickIsUsedOnlyWhileThatSongIsPinnedToAmazon() {
        val pick = AmazonTrackPick("Chosen song", "Chosen artist", "Album", 200_000)
        val editor = PreferenceSnapshot.EMPTY.toEditor().apply {
            this[SongSourceAmazonPickKey] = SongSourceAmazonPick.withOverride(null, request.mediaId, pick)
            this[SongSourceOverrideKey] = SongSourceOverride.withOverride(null, request.mediaId, AudioSourceType.AMAZON)
        }
        assertEquals(pick, request.withSourcePins(editor.snapshot(), request.mediaId).amazonPick)
        editor[SongSourceOverrideKey] = SongSourceOverride.withOverride(null, request.mediaId, AudioSourceType.QOBUZ)
        assertNull(request.withSourcePins(editor.snapshot(), request.mediaId).amazonPick)
        assertNull(request.withSourcePins(editor.snapshot(), "other-song").amazonPick)
    }
}
