package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HiddenPlaylistsTest {
    @Test fun hiddenPlaylistsListOnlyHiddenRowsAndUnhideClearsTheFlag() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val repo = RoomLibraryRepository(db.library())
            repo.createPlaylist("visible", "Visible")
            db.library().insertPlaylist(PortablePlaylistRow("hidden", "Hidden", isHidden = true))
            assertEquals(listOf("hidden"), repo.observeHiddenPlaylists().first().map { it.id })

            repo.setPlaylistHidden("hidden", false)
            assertTrue(repo.observeHiddenPlaylists().first().isEmpty())

            repo.setPlaylistHidden("visible", true)
            assertEquals(listOf("visible"), repo.observeHiddenPlaylists().first().map { it.id })
            assertFailsWith<IllegalArgumentException> { repo.setPlaylistHidden("missing", false) }
        } finally {
            db.close()
        }
    }
}
