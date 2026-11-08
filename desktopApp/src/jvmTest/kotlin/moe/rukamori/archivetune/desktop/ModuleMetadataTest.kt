package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import org.json.JSONArray
import org.json.JSONObject

class ModuleMetadataTest {
    @Test fun nestedFeedsRetainKindsAndTrackMetadata() {
        val album = JSONObject().put("id", "album").put("title", "Album")
        val track = JSONObject().put("id", "track").put("title", "Song").put("duration", 120)
            .put("artists", JSONArray().put(JSONObject().put("name", "Artist"))).put("album", album)
        val feed = JSONObject().put("sections", JSONArray().put(JSONObject().put("albums", JSONArray().put(album)))
            .put(JSONObject().put("tracks", JSONArray().put(track))))
        val rows = DesktopModuleMetadataReader.read(feed)
        assertEquals(listOf(ContentKind.ALBUM, ContentKind.SONG), rows.map { it.kind })
        assertEquals(listOf("Artist"), rows.last().artists)
        assertEquals("Album", rows.last().album)
        assertEquals(120_000L, rows.last().durationMs)
    }

    @Test fun ignoresArbitraryObjectsAndBoundsResults() {
        assertTrue(DesktopModuleMetadataReader.read(JSONObject().put("session", JSONObject().put("secret", "private"))).isEmpty())
        val tracks = JSONArray()
        repeat(220) { tracks.put(JSONObject().put("id", "$it").put("title", "Song $it")) }
        assertEquals(200, DesktopModuleMetadataReader.read(JSONObject().put("tracks", tracks)).size)
    }

    @Test fun moduleTrackIdsPreserveInstallationAndColons() {
        val row = DesktopModuleMetadataItem(ContentKind.SONG, "provider:track", "Song", listOf("Artist"))
        assertEquals("module:installation:provider:track", row.queueItem("installation", true).ref.id)
        assertEquals("metadata:installation:provider:track", row.queueItem("installation", false).ref.id)
        assertEquals("modules", row.queueItem("installation", true).ref.provider)
    }
}
