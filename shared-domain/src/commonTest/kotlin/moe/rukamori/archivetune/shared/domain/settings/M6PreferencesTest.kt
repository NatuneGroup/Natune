package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.*

class M6PreferencesTest {
    @Test fun inlinePrivacyDefaultsMatchOrdinaryConsumerAndRespectExplicitFalse() {
        val unset = PreferenceSnapshot.EMPTY
        assertTrue(unset.syncYouTubeHistory())
        assertTrue(unset.lowDataMode())
        assertEquals(unset.syncYouTubeHistory(), unset[SyncPlaybackToYouTubeHistoryKey, PrivacyPreferenceDefaults.SYNC_YOUTUBE_HISTORY])
        assertEquals(unset.lowDataMode(), unset[NetworkMeteredKey, PrivacyPreferenceDefaults.LOW_DATA_MODE])
        val explicit = PreferenceSnapshot.of(SyncPlaybackToYouTubeHistoryKey to false, NetworkMeteredKey to false)
        assertFalse(explicit.syncYouTubeHistory())
        assertFalse(explicit.lowDataMode())
    }

    @Test fun credentialsAndModelsAreProviderScopedNotSharedWithCatalogAccounts() {
        val base = PreferenceSnapshot.of(
            AiApiKeyKey to "generic-fixture", OpenRouterApiKeyKey to "router-fixture",
            DeeplApiKeyKey to "deepl-fixture", OpenRouterModelKey to "router-model",
            AiCustomModelKey to "custom-model", AiSelectedModelKey to "selected-model",
            OpenRouterBaseUrlKey to "https://router.example.test/api",
            AiCustomEndpointKey to "https://custom.example.test/api",
        )
        fun config(provider: String) = base.toEditor().apply { this[AiProviderKey] = provider }.snapshot().aiServiceSettings()
        assertEquals("router-fixture", config("OPENROUTER").apiKey)
        assertEquals("router-model", config("OPENROUTER").model)
        assertEquals("https://router.example.test/api", config("OPENROUTER").customEndpoint)
        assertEquals("deepl-fixture", config("DEEPL").apiKey)
        assertEquals("generic-fixture", config("GEMINI").apiKey)
        assertEquals("custom-model", config("CUSTOM").model)
        assertEquals("selected-model", config("GEMINI").model)
    }

    @Test fun providerOrderMigrationNormalizesDuplicatesAndMissingMembers() {
        val legacy = PreferenceSnapshot.of(LyricsProviderOrderKey to "LRCLIB,KUGOU,BETTER_LYRICS,KUGOU,UNKNOWN")
        val order = legacy.lyricsProviderOrder()
        assertEquals(LyricsProviderPreference.BETTER_LYRICS, order.first())
        assertEquals(LyricsProviderPreference.entries.size, order.distinct().size)
        assertEquals(LyricsProviderPreference.entries.toSet(), order.toSet())
        val editor = legacy.toEditor()
        assertFailsWith<IllegalArgumentException> {
            editor.setLyricsProviderOrder(listOf(LyricsProviderPreference.KUGOU, LyricsProviderPreference.KUGOU))
        }
        editor.setLyricsProviderOrder(listOf(LyricsProviderPreference.KUGOU) + order.filterNot { it == LyricsProviderPreference.KUGOU })
        assertEquals(LyricsProviderPreference.KUGOU, editor.snapshot().lyricsProviderOrder().first())
        assertEquals(LyricsProviderPreference.entries.size, editor.snapshot().lyricsProviderOrder().size)
    }
}
