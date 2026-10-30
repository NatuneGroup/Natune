package moe.rukamori.archivetune.shared.data.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramAudioMetadataTest {
    @Test
    fun tagsWinOverTheFileName() {
        assertEquals("Song" to "Artist", TelegramAudioMetadata.derive("Song (Official Video)", "Artist", "other - name.mp3"))
    }

    @Test
    fun aTaglessFileNameSplitsIntoArtistAndTitle() {
        assertEquals("Title" to "Artist", TelegramAudioMetadata.derive("", null, "01. Artist - Title [HQ].flac"))
    }

    @Test
    fun aPerformerTagKeepsTheWholeFileNameAsTheTitle() {
        assertEquals("Artist - Title" to "Tagged", TelegramAudioMetadata.derive("", "Tagged", "Artist - Title.mp3"))
    }

    @Test
    fun losslessIsReadFromTheTypeOrTheExtension() {
        assertTrue(TelegramAudioMetadata.isLossless("audio/flac", "x.bin"))
        assertTrue(TelegramAudioMetadata.isLossless("application/octet-stream", "x.WAV"))
        assertFalse(TelegramAudioMetadata.isLossless("audio/mpeg", "x.mp3"))
    }

    @Test
    fun onlyAudioDocumentsCountAsSongs() {
        assertTrue(TelegramAudioMetadata.isAudioDocument("audio/ogg", "voice"))
        assertTrue(TelegramAudioMetadata.isAudioDocument("application/octet-stream", "song.opus"))
        assertFalse(TelegramAudioMetadata.isAudioDocument("application/pdf", "book.pdf"))
    }

    @Test
    fun channelPlaylistIdsRoundTrip() {
        assertEquals("LPtg-1001234", telegramChannelPlaylistId(-1001234))
        assertEquals(-1001234L, telegramChannelChatId("LPtg-1001234"))
        assertEquals(null, telegramChannelChatId("LPabcdefgh"))
    }
}
