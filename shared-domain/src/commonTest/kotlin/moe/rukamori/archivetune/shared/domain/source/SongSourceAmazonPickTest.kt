package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.settings.*

class SongSourceAmazonPickTest {
    @Test fun androidJsonRoundTripsMetadataWithoutInventingAmazonIds() {
        val legacy = """{"song":{"title":" Song ","artist":" Artist ","album":" Album ","durationMs":180001}}"""
        val pick = AmazonTrackPick("Song", "Artist", "Album", 180_001)
        assertEquals(mapOf("song" to pick), SongSourceAmazonPick.parse(legacy))
        assertEquals(pick, SongSourceAmazonPick.get(SongSourceAmazonPick.serialize(mapOf("song" to pick)), "song"))
    }

    @Test fun malformedRowsAndNonpositiveDurationsStayUnknown() {
        for (raw in listOf(null, "", "not-json", "[]", "null")) assertTrue(SongSourceAmazonPick.parse(raw).isEmpty())
        val raw = """{"":{"title":"Song","artist":"Artist"},"bad":{"title":"Song"},"valid":{"title":"Song","artist":"Artist","durationMs":-1}}"""
        assertEquals(mapOf("valid" to AmazonTrackPick("Song", "Artist", null, null)), SongSourceAmazonPick.parse(raw))
    }

    @Test fun replacingOneSongPreservesOthersAndRemovalRestoresEmptyRepresentation() {
        val first = AmazonTrackPick("First", "Artist", null, null)
        val second = AmazonTrackPick("Second", "Artist", "Record", 200_000)
        val both = SongSourceAmazonPick.withOverride(SongSourceAmazonPick.withOverride(null, "one", first), "two", second)
        val removed = SongSourceAmazonPick.withOverride(both, "one", null)
        assertEquals(mapOf("two" to second), SongSourceAmazonPick.parse(removed))
        assertEquals("", SongSourceAmazonPick.withOverride(removed, "two", null))
    }

    @Test fun changingPrimaryInstanceClearsItsHostBoundJwtButNotTheOperatorToken() {
        val editor = PreferenceSnapshot.of(AmazonInstancesKey to "https://first.invalid\nhttps://second.invalid",
            AmazonTurnstileJwtKey to "fixture-jwt", AmazonTurnstileJwtExpiryMsKey to 10_000L,
            AmazonBypassTokenKey to "fixture-operator").toEditor()
        editor.setAmazonInstances("https://second.invalid,https://first.invalid")
        assertNull(editor[AmazonTurnstileJwtKey])
        assertNull(editor[AmazonTurnstileJwtExpiryMsKey])
        assertEquals("fixture-operator", editor[AmazonBypassTokenKey])
    }

    @Test fun normalizingOrChangingSecondaryInstancesKeepsPrimaryAuthorization() {
        val editor = PreferenceSnapshot.of(AmazonInstancesKey to "https://first.invalid\nhttps://second.invalid",
            AmazonTurnstileJwtKey to "fixture-jwt", AmazonTurnstileJwtExpiryMsKey to 10_000L).toEditor()
        editor.setAmazonInstances(" https://first.invalid/,https://third.invalid")
        assertEquals("fixture-jwt", editor[AmazonTurnstileJwtKey])
        assertEquals(10_000L, editor[AmazonTurnstileJwtExpiryMsKey])
        editor.setAmazonInstances("")
        assertNull(editor[AmazonTurnstileJwtKey])
    }
}
