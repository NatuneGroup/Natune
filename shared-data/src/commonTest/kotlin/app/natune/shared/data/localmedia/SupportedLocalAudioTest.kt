package app.natune.shared.data.localmedia

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The Android local-library scanner and the shared hosts filter files through this one list.
class SupportedLocalAudioTest {
    @Test
    fun extensionsDecideBeforeMimeType() {
        assertTrue(SupportedLocalAudio.isSupported("Song.FLAC", null))
        assertTrue(SupportedLocalAudio.isSupported("voice.3ga", "application/octet-stream"))
        assertFalse(SupportedLocalAudio.isSupported("album.ape", "audio/flac"))
        assertFalse(SupportedLocalAudio.isSupported("disc.cue", "audio/mpeg"))
    }

    @Test
    fun mimeTypeFallsBackForUnknownExtensions() {
        assertTrue(SupportedLocalAudio.isSupported("track", "Audio/MPEG; charset=binary"))
        assertTrue(SupportedLocalAudio.isSupported(null, "video/webm"))
        assertFalse(SupportedLocalAudio.isSupported("notes.txt", "text/plain"))
        assertFalse(SupportedLocalAudio.isSupported(null, null))
    }

    @Test
    fun mimeTypeCheckAcceptsTheAudioWildcard() {
        assertTrue(SupportedLocalAudio.isSupportedMimeType("audio/*"))
        assertTrue(SupportedLocalAudio.isSupportedMimeType(" audio/ogg "))
        assertFalse(SupportedLocalAudio.isSupportedMimeType("image/png"))
    }
}
