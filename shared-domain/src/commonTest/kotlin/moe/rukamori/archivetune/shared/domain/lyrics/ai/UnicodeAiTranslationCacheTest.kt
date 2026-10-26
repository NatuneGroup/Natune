package moe.rukamori.archivetune.shared.domain.lyrics.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cache identity, target invalidation and success-only storage for AI lyric translations. The provider
 * credential is a fixture value here, and every assertion checks that it never leaks into an identity.
 */
class UnicodeAiTranslationCacheTest {
    private val config = AiServiceSettings(AiProvider.CHATGPT, "test-credential", "", "model-a")
    private val configIdentity = config.cacheIdentity("salt-1")
    private val lyrics = "きみのなまえはぼくのなまえ\n二行目"

    @Test
    fun changingTheTargetInvalidatesButACanonicalSpellingKeeps() {
        assertEquals(
            AiCacheInvalidation.INVALIDATE_TARGET_CHANGED,
            AiTranslationCachePolicy.invalidationDecision("English", "Spanish"),
        )
        assertEquals(AiCacheInvalidation.KEEP, AiTranslationCachePolicy.invalidationDecision("ENGLISH", "English"))
        assertEquals(
            AiCacheInvalidation.KEEP,
            AiTranslationCachePolicy.invalidationDecision("CHINESE_SIMPLIFIED", "chinese simplified"),
        )
        assertEquals(AiCacheInvalidation.KEEP, AiTranslationCachePolicy.invalidationDecision("", "ENGLISH"))
    }

    @Test
    fun aCachedEntryIsValidOnlyForTheTargetItWasTranslatedInto() {
        val key = AiTranslationCacheKey.of(configIdentity, "ENGLISH", lyrics)
        val entry = assertNotNull(AiTranslationCacheEntry.successOrNull(key, AiTranslationAttempt.Complete("hello")))

        assertTrue(AiTranslationCachePolicy.isValidFor(entry, "English"))
        assertTrue(AiTranslationCachePolicy.isValidFor(entry, "ENGLISH"))
        assertFalse(AiTranslationCachePolicy.isValidFor(entry, "Spanish"))
    }

    @Test
    fun theKeyChangesWithTargetConfigurationAndLyricsButNotWithSpelling() {
        val base = AiTranslationCacheKey.of(configIdentity, "ENGLISH", lyrics)

        assertEquals(base, AiTranslationCacheKey.of(configIdentity, "English", lyrics))
        assertNotEquals(base, AiTranslationCacheKey.of(configIdentity, "Spanish", lyrics))
        assertNotEquals(base, AiTranslationCacheKey.of(config.cacheIdentity("salt-2"), "ENGLISH", lyrics))
        assertNotEquals(base, AiTranslationCacheKey.of(configIdentity, "ENGLISH", "$lyrics\n三行目"))
    }

    @Test
    fun wrappersAroundTheSameLyricsDoNotChangeTheFingerprint() {
        val plain = AiTranslationCacheKey.of(configIdentity, "ENGLISH", "line one\nline two")

        assertEquals(plain, AiTranslationCacheKey.of(configIdentity, "ENGLISH", "\uFEFFline one\nline two"))
        assertEquals(plain, AiTranslationCacheKey.of(configIdentity, "ENGLISH", "  line one\nline two\n  "))
        assertEquals(plain, AiTranslationCacheKey.of(configIdentity, "ENGLISH", "```\nline one\nline two\n```"))
    }

    @Test
    fun fingerprintMatchesThePublishedFnv1aVectors() {
        assertEquals(-0x340d631b7bdddcdbL, LyricsFingerprint.of(""))
        // 0xaf63dc4c8601ec8c as a signed Long, the published FNV-1a 64 value for "a".
        assertEquals(-5808556873153909620L, LyricsFingerprint.of("a"))
        assertEquals(LyricsFingerprint.of(lyrics), LyricsFingerprint.of(lyrics))
    }

    @Test
    fun onlyACompleteNonBlankAttemptBecomesASuccess() {
        val key = AiTranslationCacheKey.of(configIdentity, "ENGLISH", lyrics)

        assertNull(AiTranslationCacheEntry.successOrNull(key, AiTranslationAttempt.Partial(2, 3)))
        assertNull(
            AiTranslationCacheEntry.successOrNull(
                key,
                AiTranslationAttempt.Failed(AiTranslationFailure.NO_TRANSLATABLE_SEGMENTS),
            ),
        )
        assertNull(
            AiTranslationCacheEntry.successOrNull(key, AiTranslationAttempt.Failed(AiTranslationFailure.PROVIDER_REJECTED)),
        )
        assertNull(
            AiTranslationCacheEntry.successOrNull(key, AiTranslationAttempt.Failed(AiTranslationFailure.OBSOLETE_REQUEST)),
        )
        assertNull(AiTranslationCacheEntry.successOrNull(key, AiTranslationAttempt.Complete("")))
        assertNull(AiTranslationCacheEntry.successOrNull(key, AiTranslationAttempt.Complete(" \n\t")))
        assertNotNull(AiTranslationCacheEntry.successOrNull(key, AiTranslationAttempt.Complete("translated")))
    }

    @Test
    fun configIdentityChangesWithEveryField() {
        val base = config.cacheIdentity("salt")

        assertEquals(base, config.copy().cacheIdentity("salt"))
        assertNotEquals(base, config.copy(provider = AiProvider.GEMINI).cacheIdentity("salt"))
        assertNotEquals(base, config.copy(apiKey = "other-credential").cacheIdentity("salt"))
        assertNotEquals(base, config.copy(customEndpoint = "https://example.invalid/v1").cacheIdentity("salt"))
        assertNotEquals(base, config.copy(model = "model-b").cacheIdentity("salt"))
        assertNotEquals(base, config.copy(deeplFormality = "more").cacheIdentity("salt"))
        assertNotEquals(base, config.cacheIdentity("other-salt"))
    }

    @Test
    fun configIdentityIsOpaqueHexThatHidesTheCredential() {
        val identity = config.cacheIdentity("salt")

        assertEquals(64, identity.length)
        assertTrue(identity.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(identity.contains("test-credential"))
        assertFalse(config.toString().contains("test-credential"))
    }

    @Test
    fun lengthPrefixesStopAmbiguousPartBoundariesFromColliding() {
        assertNotEquals(
            Sha256.hexDigestOfParts(listOf("ab", "c")),
            Sha256.hexDigestOfParts(listOf("a", "bc")),
        )
    }

    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hexDigest("abc".encodeToByteArray()),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hexDigest(ByteArray(0)),
        )
        // Longer than one block after padding, so the multi-block path runs.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hexDigest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }
}
