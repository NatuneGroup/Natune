package moe.rukamori.archivetune.sponsorblock

import androidx.datastore.preferences.core.mutablePreferencesOf
import moe.rukamori.archivetune.constants.SponsorBlockApiUrlKey
import moe.rukamori.archivetune.constants.SponsorBlockCategoriesKey
import moe.rukamori.archivetune.constants.SponsorBlockEnabledKey
import moe.rukamori.archivetune.constants.SponsorBlockPodcastsEnabledKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockSettingsTest {
    @Test fun invalidPersistedOriginsDisableLookupWithoutChangingTheStoredOptIns() {
        for (origin in listOf(
            "http://segments.example.test", "https:", "https:/", "https://", "https:///", "https://:443",
            "/", "//", "https://segments.example.test/api", "https://segments.example.test//",
            "https://segments.example.test?q=x", "https://segments.example.test#fragment",
        )) {
            val prefs = preferences().apply { this[SponsorBlockApiUrlKey] = origin }
            val settings = sponsorBlockSettingsFrom(prefs)
            assertFalse(origin, settings.enabled)
            assertTrue(settings.podcastsEnabled)
            assertEquals(true, prefs[SponsorBlockEnabledKey])
            assertEquals(origin, prefs[SponsorBlockApiUrlKey])
        }
    }

    @Test fun unsetOrBlankOriginsKeepThePublicDefault() {
        for (origin in listOf(null, "", " ", "\t")) {
            val prefs = preferences().apply { origin?.let { this[SponsorBlockApiUrlKey] = it } }
            val settings = sponsorBlockSettingsFrom(prefs)
            assertTrue(settings.enabled)
            assertTrue(settings.podcastsEnabled)
            assertEquals(SPONSORBLOCK_DEFAULT_API_URL, settings.apiUrl)
            assertEquals(SponsorBlockCategory.Defaults, settings.categories)
        }
    }

    @Test fun validCustomOriginAndEmptyCategoriesRemainExplicitChoices() {
        val prefs = preferences().apply {
            this[SponsorBlockApiUrlKey] = " https://segments.example.test:8443/ "
            this[SponsorBlockCategoriesKey] = emptySet()
        }
        val settings = sponsorBlockSettingsFrom(prefs)
        assertTrue(settings.enabled)
        assertEquals("https://segments.example.test:8443", settings.apiUrl)
        assertTrue(settings.categories.isEmpty())
        prefs[SponsorBlockEnabledKey] = false
        assertFalse(sponsorBlockSettingsFrom(prefs).enabled)
        prefs[SponsorBlockEnabledKey] = true
        prefs[SponsorBlockPodcastsEnabledKey] = false
        assertFalse(sponsorBlockSettingsFrom(prefs).podcastsEnabled)
    }

    private fun preferences() = mutablePreferencesOf(
        SponsorBlockEnabledKey to true,
        SponsorBlockPodcastsEnabledKey to true,
    )
}
