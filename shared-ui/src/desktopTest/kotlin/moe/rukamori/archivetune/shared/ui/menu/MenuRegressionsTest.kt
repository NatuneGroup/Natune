package moe.rukamori.archivetune.shared.ui.menu

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MenuRegressionsTest {
    @Test fun everySplitArtistRetainsItsOriginalChannel() {
        val choices = splitArtistChoices("A & B, C", "channel-id", "&,")
        assertEquals(listOf("A", "B", "C"), choices.map { it.name })
        assertEquals(listOf("channel-id", "channel-id", "channel-id"), choices.map { it.original })
        assertEquals(listOf("A & B"), splitArtistChoices("A & B", "channel-id", "").map { it.name })
    }

    @Test fun telegramUsesActualMessageUrlAndNeverYoutubeFallback() = runBlocking {
        val port = Sharing()
        val handler = MenuShareHandler(port)
        assertTrue(handler.song("telegram://track/-100123/10485760/7", false))
        assertEquals(listOf("https://t.me/c/123/10"), port.links)
        port.messageLink = null
        assertFalse(handler.song("telegram://track/-100123/10485760/7", false))
        assertEquals(1, port.links.size)
    }

    @Test fun localSharesFileAndCatalogSharesWatchUrl() = runBlocking {
        val port = Sharing()
        val handler = MenuShareHandler(port)
        handler.song("local-id", true, "audio/flac")
        handler.song("catalog-id", false)
        assertEquals(listOf<Pair<String, String?>>("local-id" to "audio/flac"), port.files)
        assertEquals(listOf("https://music.youtube.com/watch?v=catalog-id"), port.links)
    }

    @Test fun localPlaylistNeverProducesListNullAndExportIsExplicit() = runBlocking {
        val port = Sharing()
        val handler = MenuShareHandler(port)
        assertFalse(handler.playlist(null, null))
        assertTrue(port.links.isEmpty())
        assertTrue(handler.playlist("PLabc", null))
        assertEquals(listOf("https://music.youtube.com/playlist?list=PLabc"), port.links)
        assertTrue(handler.playlist(null, "https://t.me/channel"))
        assertTrue(port.links.none { it.contains("list=null") })
    }

    @Test fun ordinaryRebuildPersistsAndUndoCannotOverwriteANewerEdit() = runBlocking {
        var current = LyricsEditSnapshot("track", "[00:01.00]Hello", "provider", "LrcLib")
        val edits = LyricsMenuEdits { before, after, guard ->
            if (current != before || !guard()) null else { current = after; current }
        }
        val original = current
        assertTrue(edits.translate(original, mapOf(0 to "Bonjour")))
        assertEquals("[00:01.00]Hello\n[00:01.00]Bonjour", current.lyrics)
        assertEquals("TRANSLATION", current.source)
        assertTrue(edits.undo())
        assertEquals(original, current)
        assertTrue(edits.translate(original, mapOf(0 to "Again")))
        current = current.copy(lyrics = "New user edit")
        assertFalse(edits.undo())
        assertEquals("New user edit", current.lyrics)
    }

    @Test fun obsoleteTranslationDoesNotCreateUndoOrWrite() = runBlocking {
        var writes = 0
        val edits = LyricsMenuEdits { _, after, guard -> if (guard()) { writes++; after } else null }
        assertFalse(edits.apply(LyricsEditSnapshot("track", "Hello", "provider", "LrcLib"), "Bonjour", isCurrent = { false }))
        assertEquals(0, writes)
        assertNull(edits.undo.value)
    }

    @Test fun copyLinkReusesTheShareUrlPolicy() = runBlocking {
        val port = Sharing()
        val handler = MenuShareHandler(port)
        assertTrue(handler.copySongLink("catalog-id", false))
        assertFalse(handler.copySongLink("local-id", true))
        assertTrue(handler.copySongLink("telegram://track/-100123/10485760/7", true))
        assertEquals(
            listOf(
                "https://music.youtube.com/watch?v=catalog-id",
                "https://t.me/c/123/10",
            ),
            port.copied,
        )
        port.messageLink = null
        assertFalse(handler.copySongLink("telegram://track/-100123/10485760/7", false))
        assertEquals(2, port.copied.size)
    }

    @Test fun copyPlaylistAndArtistLinksMatchShareTargets() = runBlocking {
        val port = Sharing()
        val handler = MenuShareHandler(port)
        assertFalse(handler.copyPlaylistLink(null, null))
        assertTrue(handler.copyPlaylistLink("PLabc", null))
        assertTrue(handler.copyPlaylistLink(null, "https://t.me/channel"))
        assertFalse(handler.copyArtistLink("Local artist"))
        assertTrue(handler.copyArtistLink("UCxyz"))
        assertEquals(
            listOf(
                "https://music.youtube.com/playlist?list=PLabc",
                "https://t.me/channel",
                "https://music.youtube.com/channel/UCxyz",
            ),
            port.copied,
        )
    }

    @Test fun shareLinkAtPositionAppendsYouTubeTimestamp() {
        assertEquals(
            "https://music.youtube.com/watch?v=abc&t=61",
            shareLinkAtPosition("https://music.youtube.com/watch?v=abc", 61_400L),
        )
        assertEquals(
            "https://music.youtube.com/watch?v=abc",
            shareLinkAtPosition("https://music.youtube.com/watch?v=abc", 400L),
        )
        assertEquals(
            "https://music.youtube.com/watch?v=abc",
            shareLinkAtPosition("https://music.youtube.com/watch?v=abc", -5_000L),
        )
        assertEquals(
            "https://t.me/c/1/2?t=30",
            shareLinkAtPosition("https://t.me/c/1/2", 30_000L),
        )
    }
    private class Sharing : MenuSharing {
        val links = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val files = mutableListOf<Pair<String, String?>>()
        var messageLink: String? = "https://t.me/c/123/10"
        override suspend fun shareLink(url: String) { links += url }
        override suspend fun copyLink(url: String) { copied += url }
        override suspend fun shareLocalAudio(mediaId: String, mimeType: String?) { files += mediaId to mimeType }
        override suspend fun telegramMessageLink(mediaId: String): String? = messageLink
    }
}
