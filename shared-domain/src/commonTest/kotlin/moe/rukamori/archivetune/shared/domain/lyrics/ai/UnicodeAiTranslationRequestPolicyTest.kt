/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the cache-target and eligibility parts of the Android `AiLyricsRequestRegressionTest`, plus the
 * romanisation gates from `AiLyricsRomanization`. The app-side document rebuild and HTTP paths are not
 * shared-domain behaviour, so they are not covered here.
 */
class UnicodeAiTranslationRequestPolicyTest {
    private val config = AiServiceSettings(AiProvider.CHATGPT, "test-credential", "", "model-a")
    private val deepl = AiServiceSettings(AiProvider.DEEPL, "test-credential", "", "")
    private val japanese = "きみのなまえはぼくのなまえ"

    private fun snapshot(
        config: AiServiceSettings = this.config,
        autoTranslate: Boolean = true,
        target: String? = null,
        excluded: Set<String> = emptySet(),
    ) = AiTranslationSettingsSnapshot(config, autoTranslate, target, excluded)

    @Test
    fun targetDefaultsToEnglishAndCanonicalisesSpelling() {
        assertEquals("ENGLISH", AiTargetLanguage.requested(""))
        assertEquals("ENGLISH", AiTargetLanguage.requested("   "))
        assertEquals("Spanish", AiTargetLanguage.requested("Spanish"))
        assertEquals("English", AiTargetLanguage.canonical("ENGLISH"))
        assertEquals("English", AiTargetLanguage.canonical(""))
        assertEquals("Chinese simplified", AiTargetLanguage.canonical("CHINESE_SIMPLIFIED"))
    }

    @Test
    fun deepLTargetCodesFollowThePickerLabels() {
        assertEquals("EN-US", DeepLTargets.targetCode("ENGLISH"))
        assertEquals("EN-US", DeepLTargets.targetCode("english"))
        assertEquals("ZH-HANS", DeepLTargets.targetCode("CHINESE_SIMPLIFIED"))
        assertEquals("ZH-HANT", DeepLTargets.targetCode("Chinese traditional"))
        assertEquals("PT-PT", DeepLTargets.targetCode("PORTUGUESE"))
        assertEquals("JA", DeepLTargets.targetCode("JAPANESE"))
        assertNull(DeepLTargets.targetCode("HINDI"))
        assertNull(DeepLTargets.targetCode("KLINGON"))
    }

    @Test
    fun storedTargetOverridesTheRequestTargetOnlyWhenNotBlank() {
        assertEquals("ENGLISH", snapshot().effectiveTarget("ENGLISH"))
        assertEquals("ENGLISH", snapshot(target = "").effectiveTarget("ENGLISH"))
        assertEquals("ENGLISH", snapshot(target = "  ").effectiveTarget("ENGLISH"))
        assertEquals("Spanish", snapshot(target = "Spanish").effectiveTarget("ENGLISH"))

        assertFalse(snapshot(target = "").isTargetChanged("ENGLISH"))
        assertFalse(snapshot(target = "ENGLISH").isTargetChanged("ENGLISH"))
        assertTrue(snapshot(target = "Spanish").isTargetChanged("ENGLISH"))
    }

    @Test
    fun preflightChecksInTheAndroidOrder() {
        assertEquals(AiTranslationPreflight.BlankLyrics, snapshot().preflight("  ", "ENGLISH", emptyList(), automatic = false))
        assertEquals(
            AiTranslationPreflight.TargetChanged,
            snapshot(target = "Spanish").preflight(japanese, "ENGLISH", emptyList(), automatic = false),
        )
        assertEquals(
            AiTranslationPreflight.NotConfigured,
            snapshot(config = config.copy(apiKey = "")).preflight(japanese, "ENGLISH", emptyList(), automatic = false),
        )
        assertEquals(
            AiTranslationPreflight.UnsupportedDeepLTarget,
            snapshot(config = deepl).preflight(japanese, "KLINGON", emptyList(), automatic = false),
        )
        assertEquals(
            AiTranslationPreflight.AutomaticDisabled,
            snapshot(autoTranslate = false).preflight(japanese, "ENGLISH", emptyList(), automatic = true),
        )
        assertEquals(
            AiTranslationPreflight.AutomaticExcluded,
            snapshot(excluded = setOf("JAPANESE")).preflight(japanese, "ENGLISH", emptyList(), automatic = true),
        )
    }

    @Test
    fun manualRequestsIgnoreTheAutomaticGatesButStillNeedAConfiguredProvider() {
        val excluded = snapshot(autoTranslate = false, excluded = setOf("JAPANESE"))

        assertEquals(AiTranslationPreflight.Ready, excluded.preflight(japanese, "ENGLISH", emptyList(), automatic = false))
        assertEquals(AiTranslationPreflight.Ready, snapshot(config = deepl).preflight(japanese, "ENGLISH", emptyList(), false))
        assertEquals(AiTranslationPreflight.Ready, snapshot().preflight(japanese, "ENGLISH", emptyList(), automatic = true))
    }

    @Test
    fun anExactSourceHintDecidesEligibilityAloneOverTheDetectedScript() {
        val japaneseExcluded = snapshot(excluded = setOf("JAPANESE"))

        // The hint says Korean, so the Japanese-looking script does not exclude these lyrics.
        assertEquals(
            AiTranslationPreflight.Ready,
            japaneseExcluded.preflight(japanese, "ENGLISH", listOf("ko"), automatic = true),
        )
        assertEquals(
            AiTranslationPreflight.AutomaticExcluded,
            japaneseExcluded.preflight(japanese, "ENGLISH", listOf("ja"), automatic = true),
        )
    }

    @Test
    fun requestIdentityChangesWhenAnyStalenessFieldChanges() {
        val base = snapshot().identity("ENGLISH", "salt")

        assertEquals(base, snapshot().identity("ENGLISH", "salt"))
        // A stored target equal to the request target is not a change, so the identity must not move.
        assertEquals(base, snapshot(target = "ENGLISH").identity("ENGLISH", "salt"))
        assertNotEquals(base, snapshot(config = config.copy(model = "model-b")).identity("ENGLISH", "salt"))
        assertNotEquals(base, snapshot(autoTranslate = false).identity("ENGLISH", "salt"))
        assertNotEquals(base, snapshot(target = "Spanish").identity("ENGLISH", "salt"))
        assertNotEquals(base, snapshot(excluded = setOf("HINDI")).identity("ENGLISH", "salt"))
        assertNotEquals(base, snapshot().identity("Spanish", "salt"))
        assertNotEquals(base, snapshot().identity("ENGLISH", "other-salt"))
    }

    @Test
    fun segmentBatchRejectsCountMismatchAndBlankSegmentsWithoutSubstitution() {
        assertEquals(AiSegmentBatchCheck.Accepted(listOf("a", "b")), AiSegmentBatchPolicy.validate(2, listOf("a", "b")))
        assertEquals(
            AiSegmentBatchCheck.Incomplete(usableSegments = 1, expectedSegments = 2),
            AiSegmentBatchPolicy.validate(2, listOf("a")),
        )
        assertEquals(
            AiSegmentBatchCheck.Incomplete(usableSegments = 1, expectedSegments = 2),
            AiSegmentBatchPolicy.validate(2, listOf("a", " ")),
        )
        // Extra segments never inflate the usable count past what was asked for.
        assertEquals(
            AiSegmentBatchCheck.Incomplete(usableSegments = 2, expectedSegments = 2),
            AiSegmentBatchPolicy.validate(2, listOf("a", "b", "c")),
        )
    }

    @Test
    fun chunkingRespectsItemAndCharacterBudgets() {
        assertEquals(emptyList<IntRange>(), AiSegmentBatchPolicy.chunkByBudget(emptyList()))
        assertEquals(listOf(0 until 80, 80 until 100), AiSegmentBatchPolicy.chunkByBudget(List(100) { 1 }))
        assertEquals(listOf(0 until 2, 2 until 3), AiSegmentBatchPolicy.chunkByBudget(listOf(3000, 3000, 3000)))
        // An over-long segment is never dropped; it gets a batch of its own.
        assertEquals(listOf(0 until 1, 1 until 2), AiSegmentBatchPolicy.chunkByBudget(listOf(7000, 10)))
    }

    @Test
    fun aiServiceNeedsAProviderCredentialAndACustomEndpoint() {
        assertFalse(AiServiceSettings(AiProvider.NONE, "test-credential", "", "").canCallApi)
        assertFalse(AiServiceSettings(AiProvider.CHATGPT, "  ", "", "model").canCallApi)
        assertFalse(AiServiceSettings(AiProvider.CUSTOM, "test-credential", "", "model").canCallApi)
        assertTrue(AiServiceSettings(AiProvider.CUSTOM, "test-credential", "https://example.invalid/v1", "m").canCallApi)
        assertTrue(config.supportsRomanization)
        assertFalse(deepl.supportsRomanization)
    }

    @Test
    fun romanisationNeedsAnActiveNonDeepLProviderAndNoExclusion() {
        val enabled = AiRomanizationSettings(enabled = true, auto = true, excludedLanguages = emptySet(), config = config)

        assertTrue(AiRomanizationPolicy.isEligible(enabled, listOf(japanese)))
        assertFalse(AiRomanizationPolicy.isEligible(enabled.copy(enabled = false), listOf(japanese)))
        assertFalse(AiRomanizationPolicy.isEligible(enabled.copy(config = deepl), listOf(japanese)))
        assertFalse(AiRomanizationPolicy.isEligible(enabled.copy(config = config.copy(apiKey = "")), listOf(japanese)))
        assertFalse(AiRomanizationPolicy.isEligible(enabled, emptyList()))
        assertFalse(AiRomanizationPolicy.isEligible(enabled.copy(excludedLanguages = setOf("JAPANESE")), listOf(japanese)))
        // A configured but not automatic setup still counts as active, so it serves on-demand requests.
        assertTrue(AiRomanizationPolicy.isEligible(enabled.copy(auto = false), listOf(japanese)))
    }

    @Test
    fun latinOnlyLyricsNeverSpendARomanisationRequest() {
        assertFalse(AiRomanizationPolicy.needsRequest(listOf("Hello", "")))
        assertTrue(AiRomanizationPolicy.needsRequest(listOf("Hello", japanese)))
    }

    @Test
    fun romanisationSessionKeyChangesWithTrackTextOrHints() {
        val base = AiRomanizationPolicy.sessionKey("track-1", "[00:01.00]hello")

        assertEquals(base, AiRomanizationPolicy.sessionKey("track-1", "[00:01.00]hello"))
        assertNotEquals(base, AiRomanizationPolicy.sessionKey("track-2", "[00:01.00]hello"))
        assertNotEquals(base, AiRomanizationPolicy.sessionKey("track-1", "[00:01.00]hello!"))
        assertNotEquals(base, AiRomanizationPolicy.sessionKey("track-1", "[00:01.00]hello", listOf("ja")))
        assertEquals(
            AiRomanizationPolicy.hintIdentity(listOf("ja", "ko", "ja")),
            AiRomanizationPolicy.hintIdentity(listOf("KOREAN", "Japanese")),
        )
    }

    @Test
    fun anUnchangedEchoIsNotACandidateRomanisation() {
        assertNull(AiRomanizationPolicy.candidateLine("Hello", null))
        assertNull(AiRomanizationPolicy.candidateLine("Hello", "   "))
        assertNull(AiRomanizationPolicy.candidateLine("Hello", " hello "))
        assertEquals("konnichiwa", AiRomanizationPolicy.candidateLine(japanese, " konnichiwa "))
    }

    @Test
    fun romanisationIsCacheableOnlyWhenEveryRomanizableLineIsPresent() {
        val lines = listOf(japanese, "", "hello")

        assertTrue(AiRomanizationPolicy.isCacheable(lines, listOf("a", null, "b")))
        assertFalse(AiRomanizationPolicy.isCacheable(lines, listOf("a", null, null)))
        assertTrue(AiRomanizationPolicy.isCacheable(listOf(japanese), listOf("a")))
        assertFalse(AiRomanizationPolicy.isCacheable(listOf(japanese, japanese), listOf("a")))
        assertFalse(AiRomanizationPolicy.isCacheable(listOf("", "  "), listOf(null, null)))
    }

    @Test
    fun romanisationIsPublishableOnlyWhenEveryRomanizableLineIsMapped() {
        val lines = listOf(japanese, "hello")

        assertTrue(AiRomanizationPolicy.isPublishable(lines, mapOf(japanese to "konnichiwa")))
        assertFalse(AiRomanizationPolicy.isPublishable(lines, emptyMap()))
        assertFalse(AiRomanizationPolicy.isPublishable(lines, mapOf("hello" to "hello")))
    }
}
