package app.natune.shared.data.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import app.natune.shared.domain.platform.AcceptedAccount
import app.natune.shared.domain.platform.AcceptedAccountReader
import app.natune.shared.domain.platform.CredentialScope
import app.natune.shared.domain.platform.ProviderIdentity
import app.natune.shared.domain.queue.IncompleteFinitePlaylistException
import app.natune.shared.domain.repository.ContentAccountChanged
import app.natune.shared.domain.repository.ContentKind
import app.natune.shared.domain.repository.ContentProvider
import app.natune.shared.domain.repository.ContentRequest
import app.natune.shared.domain.repository.ContentSurface

class YouTubeHistoryAccountPagingTest {
    private val history = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.HISTORY)

    @Test fun accountWrappedCursorReachesTheNextPageUnchangedAndKeepsEpisodes() = runTest {
        val fixture = Fixture()
        fixture.answer = { token ->
            if (token == null) page("one", "older+/=|?page")
            else {
                assertEquals("older+/=|?page", token)
                page("two")
            }
        }
        val first = fixture.repository.page(history)
        assertEquals(ContentKind.EPISODE, first.items.single().item.ref.kind)
        val wrapped = assertNotNull(first.continuation)
        assertNotEquals("older+/=|?page", wrapped)
        val second = fixture.repository.page(history, wrapped)
        assertEquals("two", second.items.single().item.ref.id)
        assertNotEquals(first.items.single().occurrenceId, second.items.single().occurrenceId)
        assertNull(second.continuation)
        assertEquals(listOf(null, "older+/=|?page"), fixture.calls)
    }

    @Test fun changedAccountRefusesThePreviousCursorBeforeAnotherTransportRead() = runTest {
        val fixture = Fixture()
        val first = fixture.repository.page(history)
        fixture.accepted = fixture.accepted.copy(generation = 2L)
        assertFailsWith<ContentAccountChanged> { fixture.repository.page(history, first.continuation) }
        assertEquals(listOf<String?>(null), fixture.calls)
    }

    @Test fun anAccountSwitchDuringTheReadDiscardsItsPage() = runTest {
        val fixture = Fixture()
        fixture.answer = {
            fixture.accepted = fixture.accepted.copy(generation = 2L)
            page("old-account-row")
        }
        assertFailsWith<ContentAccountChanged> { fixture.repository.page(history) }
    }

    @Test fun failureAndRepeatedCursorNeverTurnIntoAnEmptyCompletedHistory() = runTest {
        val fixture = Fixture()
        val first = fixture.repository.page(history)
        fixture.answer = { error("History read failed") }
        assertFailsWith<IllegalStateException> { fixture.repository.page(history, first.continuation) }
        fixture.answer = { page("two", "older") }
        assertFailsWith<IncompleteFinitePlaylistException> { fixture.repository.page(history, first.continuation) }
    }

    private class Fixture {
        var accepted = AcceptedAccount(ProviderIdentity("youtube", CredentialScope.PERSONAL, "test-account"), 1L)
        val calls = ArrayList<String?>()
        var answer: (String?) -> YouTubeEntryPage = { page("one", "older") }
        private val transport = object : YouTubeAccountTransport {
            override fun signedIn() = true
            override suspend fun history(continuation: String?): YouTubeEntryPage {
                calls += continuation
                return answer(continuation)
            }
            override suspend fun listing(listing: YouTubeListing, continuation: String?): YouTubeEntryPage =
                error("History must not read a different listing")
            override suspend fun likeSong(videoId: String, liked: Boolean): Unit = error("Read-only fixture")
            override suspend fun likeAlbum(browseId: String, liked: Boolean): Unit = error("Read-only fixture")
            override suspend fun subscribeArtist(browseId: String, subscribed: Boolean): Unit = error("Read-only fixture")
        }
        val repository = YouTubeContentRepository(
            transport = object : YouTubeBrowseTransport {
                override suspend fun page(browse: YouTubeBrowse, continuation: String?): YouTubeEntryPage =
                    error("Account history must not read an anonymous browse")
            },
            account = YouTubeAccountBinding(
                transport,
                object : AcceptedAccountReader {
                    override suspend fun acceptedAccount(provider: String, accountScope: String): AcceptedAccount = accepted
                },
                personalScope = { "personal" },
                syncEnabled = { true },
            ),
        )
    }

    private companion object {
        fun page(id: String, cursor: String? = null) =
            YouTubeEntryPage(listOf(YouTubeEntry(ContentKind.EPISODE, id, "Episode $id")), cursor)
    }
}
