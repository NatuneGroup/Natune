package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.QueueSourceRecovery
import moe.rukamori.archivetune.shared.domain.repository.toPlaybackLocator

class StreamRecoveryContractTest {
    @Test
    fun aFreshAttemptCannotEnableADisabledSourceOrYouTube() {
        val settings = PlaybackSourceSnapshot(AudioSourceConfig.DEFAULT_ORDER,
            setOf(AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE))
        val refresh = QueueSourceRecovery(refreshSource = AudioSourceType.TIDAL)
        assertEquals(emptySet(), refresh.settings(settings).enabled)
        assertEquals(AudioSourceType.TIDAL, refresh.pinned(AudioSourceType.QOBUZ))
        assertEquals(setOf(AudioSourceType.QOBUZ),
            QueueSourceRecovery(refreshSource = AudioSourceType.QOBUZ).settings(settings).enabled)
    }

    @Test
    fun fallbackDropsARejectedPinWithoutChangingTheSavedOrderOrEnabledSet() {
        val settings = PlaybackSourceSnapshot(AudioSourceConfig.DEFAULT_ORDER,
            setOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE))
        val fallback = QueueSourceRecovery(rejectedSources = setOf(AudioSourceType.TIDAL))
        assertEquals(settings, fallback.settings(settings))
        assertNull(fallback.pinned(AudioSourceType.TIDAL))
        assertEquals(AudioSourceType.QOBUZ, fallback.pinned(AudioSourceType.QOBUZ))
    }

    @Test
    fun remoteAndDeezerLocatorsKeepTheAuthorizationWindowAndSource() {
        val stream = DirectStream("https://cdn.invalid/audio", "audio/flac", "flac", null,
            "served", AudioSourceType.DEEZER, expiresAtEpochMs = 1_800_000_000_000L)
        val remote = stream.toPlaybackLocator()
        assertEquals(stream.expiresAtEpochMs, remote.expiresAtEpochMs)
        assertEquals(stream.source, remote.source)
        val encrypted = PreparedAudio.DeezerEncrypted(stream, "7", "account").toPlaybackLocator()
        assertEquals(stream.expiresAtEpochMs, encrypted.expiresAtEpochMs)
        assertEquals(stream.source, encrypted.source)
        assertNull(stream.copy(expiresAtEpochMs = null).toPlaybackLocator().expiresAtEpochMs)
    }
}
