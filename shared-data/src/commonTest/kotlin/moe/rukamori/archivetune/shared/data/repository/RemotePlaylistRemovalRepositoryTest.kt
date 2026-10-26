package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistOccurrence
import moe.rukamori.archivetune.shared.domain.repository.*

class RemotePlaylistRemovalRepositoryTest {
    @Test fun exactOccurrenceCommitsRemoteBeforeLocalAndDoesNotRemoveDuplicateSibling() = runTest {
        val calls = mutableListOf<String>()
        val remote = object : RemotePlaylistEntries {
            override suspend fun setVideoIds(playlistId: String, songId: String): List<String> = error("must use exact occurrence")
            override suspend fun remove(playlistId: String, songId: String, setVideoId: String) { calls += "remote:$setVideoId" }
        }
        val row = PlaylistOccurrence(11, "same-song", 2, "set-b")
        RemotePlaylistRemovalRepository(remote) { _, occurrence -> calls += "local:${occurrence.id}" }.remove("PLfixture", row)
        assertEquals(listOf("remote:set-b", "local:11"), calls)
    }
    @Test fun failedOrCancelledRemoteNeverMutatesLocal() = runTest {
        for (failure in listOf(IllegalStateException("fixture"), CancellationException("fixture"))) {
            val remote = object : RemotePlaylistEntries {
                override suspend fun setVideoIds(playlistId: String, songId: String) = listOf("set-a")
                override suspend fun remove(playlistId: String, songId: String, setVideoId: String) { throw failure }
            }
            var local = false
            val repo = RemotePlaylistRemovalRepository(remote) { _, _ -> local = true }
            assertFails { repo.remove("PLfixture", PlaylistOccurrence(1, "song", 0)) }
            assertFalse(local)
        }
    }
    @Test fun missingSetIdRequiresUnambiguousWholeListLookupAndLocalFailureIsNotSuccess() = runTest {
        var ids = listOf("a", "b")
        var removes = 0
        val remote = object : RemotePlaylistEntries {
            override suspend fun setVideoIds(playlistId: String, songId: String) = ids
            override suspend fun remove(playlistId: String, songId: String, setVideoId: String) { removes++ }
        }
        val repo = RemotePlaylistRemovalRepository(remote) { _, _ -> error("disk fixture") }
        assertFailsWith<AmbiguousPlaylistOccurrence> { repo.remove("PLfixture", PlaylistOccurrence(1, "song", 0)) }
        assertEquals(0, removes)
        ids = listOf("a")
        assertFailsWith<RemoteRemovalNeedsReconciliation> { repo.remove("PLfixture", PlaylistOccurrence(1, "song", 0)) }
        assertEquals(1, removes)
    }
}
