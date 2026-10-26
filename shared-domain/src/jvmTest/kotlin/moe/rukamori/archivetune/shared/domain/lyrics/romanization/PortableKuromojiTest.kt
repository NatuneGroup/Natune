/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Existing resolved dependency is the fixture. No network, tiny dictionary or missing-fixture skip. */
private object IpadicFixture {
    val resources: Map<String, ByteArray> by lazy {
        val jar = File(Tokenizer::class.java.protectionDomain.codeSource.location.toURI())
        val digest = MessageDigest.getInstance("SHA-256")
        assertEquals(KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toLong(), jar.length())
        jar.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        assertEquals(KuromojiJapaneseTokenizerFactory.ARCHIVE_SHA256, digest.digest().hex())
        ZipFile(jar).use { zip ->
            KuromojiJapaneseTokenizerFactory.resources.associate { resource ->
                val entry = assertNotNull(zip.getEntry(KuromojiJapaneseTokenizerFactory.RESOURCE_PREFIX + resource.name))
                assertEquals(resource.sizeBytes.toLong(), entry.size)
                val bytes = zip.getInputStream(entry).use { it.readNBytes(resource.sizeBytes + 1) }
                assertEquals(resource.sizeBytes, bytes.size)
                assertEquals(resource.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).hex())
                resource.name to bytes
            }
        }.also {
            println("Verified Kuromoji 0.9.0 fixture: ${jar.length()} bytes; ${KuromojiJapaneseTokenizerFactory.ARCHIVE_SHA256}; eight resource pins independently verified")
        }
    }
    val baseline: Tokenizer by lazy { Tokenizer() }
    val portable: PortableKuromojiJapaneseTokenizer by lazy {
        runBlocking { open() }
    }
    suspend fun open(limits: KuromojiLimits = KuromojiLimits()): PortableKuromojiJapaneseTokenizer =
        assertNotNull(KuromojiJapaneseTokenizerFactory.create({ name, max ->
            resources[name]?.also { assertTrue(it.size <= max) }
        }, limits))
    fun expected(text: String): List<JapaneseToken> = baseline.tokenize(text).map {
        JapaneseToken(it.surface, it.reading?.takeUnless { r -> r.isEmpty() || r == "*" }, it.position)
    }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

class PortableKuromojiTest {
    private fun assertBaseline(text: String) {
        val actual = IpadicFixture.portable.tokenize(text)
        assertEquals(IpadicFixture.expected(text), actual, "Input: $text")
        assertEquals(text, actual.joinToString("") { it.surface })
        var offset = 0
        for (token in actual) {
            assertEquals(offset, token.position)
            assertEquals(token.surface, text.substring(token.position, token.position + token.surface.length))
            offset += token.surface.length
        }
    }

    @Test fun actualPhrasesAndPunctuationMatchBaseline() {
        listOf(
            "", "日本語", "東京", "お寿司が食べたい。", "すもももももももものうち", "関西国際空港",
            "今日は、東京へ行きます。明日は大阪です。", "夢ならばどれほどよかったでしょう",
            "君の名は。", "一二三四五六七八九十", "東京都に住んでいます", "仮名交じりの歌詞",
            "ＡＢＣ１２３とABC123", "・・・・、。。！？，, .\n\t\r", "カタカナ・ひらがな",
            "ありがとう　世界！", "日本語 English 12345 한국어 中文 العربية",
        ).forEach(::assertBaseline)
        assertEquals("ニホンゴ", IpadicFixture.portable.tokenize("日本語").single().reading)
        assertEquals("トウキョウ", IpadicFixture.portable.tokenize("東京").single().reading)
    }

    @Test fun unknownSurrogatesCombiningAndJoinersKeepBaselineUtf16Positions() {
        listOf("🦄東京", "東京🦄日本語", "a\u0301とe\u0301", "か\u3099とガ", "👩‍👩‍👧‍👦の歌",
            "\uD800日本語\uDC00", "𠮷野家", "❤️️と☃", "xyzzy_qwerty", "\u0000日本語", "a\u200Db",
            " \n🦄\t日本語。🦄東京", "\uFEFF東京\u2028").forEach(::assertBaseline)
        assertNull(IpadicFixture.portable.tokenize("🦄").single().reading)
        assertEquals(2, IpadicFixture.portable.tokenize("🦄東京").last().position)
    }

    @Test fun deterministicMixedCorpusMatchesRealBaseline() {
        val random = Random(9463)
        val pieces = listOf("東京", "日本語", "歌", "君", "の", "は", "ない", "た", "すもも", "ABC", "１９",
            "カナ", "ぁ", "っ", "゙", "xyzzy", "・", "、", "。", "！", " ", "\n", "🦄", "𠮷", "韓", "❤", "\u200D")
        repeat(2000) {
            assertBaseline(buildString { repeat(random.nextInt(1, 18)) { append(pieces[random.nextInt(pieces.size)]) } })
        }
        println("Portable common Kotlin matched real JVM Kuromoji on 2,000 deterministic mixed-input cases")
    }

    @Test fun realTokenizerFeedsExistingRomajiAndConservativeWordAlignment() {
        assertEquals("nihongo", romanizeJapaneseWithTokenizer("日本語", IpadicFixture.portable))
        assertEquals("toukyou", romanizeJapaneseWithTokenizer("東京", IpadicFixture.portable))
        assertEquals("🦄", romanizeJapaneseWithTokenizer("🦄", IpadicFixture.portable))
        assertEquals(listOf<String?>(null, null),
            romanizeJapaneseWordsForLine(listOf("東", "京"), "東京", IpadicFixture.portable))
        assertEquals(listOf("toukyou"),
            romanizeJapaneseWordsForLine(listOf("東京"), "東京", IpadicFixture.portable))
    }

    @Test fun everyResourceIsRequiredAndCorruptionCannotBecomeReady() = runBlocking {
        for (resource in KuromojiJapaneseTokenizerFactory.resources) {
            assertNull(KuromojiJapaneseTokenizerFactory.create({ name, _ ->
                if (name == resource.name) null else IpadicFixture.resources[name]
            }), resource.name)
            assertNull(KuromojiJapaneseTokenizerFactory.create({ name, _ ->
                IpadicFixture.resources[name]?.let { bytes ->
                    if (name != resource.name) bytes else bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                }
            }), resource.name)
        }
    }

    @Test fun emptyOversizedAndTruncatedDataAreRejectedBeforeParsing() = runBlocking {
        for (delta in listOf(-1, 1)) {
            assertNull(KuromojiJapaneseTokenizerFactory.create({ _, bound -> ByteArray(bound + delta) }))
        }
        assertNull(KuromojiJapaneseTokenizerFactory.create({ _, _ -> byteArrayOf() }))
    }

    @Test fun resolverFailureAndCancellationPropagateWithoutReadiness() {
        assertFailsWith<IllegalStateException> {
            runBlocking { KuromojiJapaneseTokenizerFactory.create({ _, _ -> error("I/O failed") }) }
        }
        var calls = 0
        assertFailsWith<CancellationException> {
            runBlocking { KuromojiJapaneseTokenizerFactory.create({ name, _ ->
                if (++calls == 3) throw CancellationException("Cancelled extraction")
                IpadicFixture.resources[name]
            }) }
        }
        assertEquals(3, calls)
        assertFailsWith<CancellationException> {
            IpadicFixture.portable.tokenize("東京") { throw CancellationException("Cancelled analysis") }
        }
    }

    @Test fun inputNodesAndWorkAreBoundedNotPartialSuccess() = runBlocking {
        assertFailsWith<IllegalArgumentException> { IpadicFixture.portable.tokenize("あ".repeat(16385)) }
        val tinyInput = IpadicFixture.open(KuromojiLimits(maxInputCodeUnits = 1))
        assertEquals(IpadicFixture.expected("東"), tinyInput.tokenize("東"))
        assertFailsWith<IllegalArgumentException> { tinyInput.tokenize("東京") }
        for (limits in listOf(KuromojiLimits(maxNodes = 3), KuromojiLimits(maxWork = 50))) {
            val tokenizer = IpadicFixture.open(limits)
            assertFailsWith<IllegalArgumentException> { tokenizer.tokenize("東京日本語".repeat(50)) }
        }
        var checkpoints = 0
        assertFailsWith<CancellationException> {
            IpadicFixture.portable.tokenize("あ".repeat(8000)) {
                if (++checkpoints == 2) throw CancellationException("Cancelled long analysis")
            }
        }
        assertEquals(2, checkpoints)
    }

    @Test fun callerCannotChangePinsOrLoadedDictionaryAfterValidation() = runBlocking {
        val exposed = KuromojiJapaneseTokenizerFactory.resources as MutableList
        exposed[0] = exposed[0].copy(sha256 = "0".repeat(64))
        assertEquals("01c110da81135a32205da569b0378cab327b804c82341651bc55bd54b9d13370",
            KuromojiJapaneseTokenizerFactory.resources[0].sha256)
        val supplied = IpadicFixture.resources.mapValues { it.value.copyOf() }
        val tokenizer = assertNotNull(KuromojiJapaneseTokenizerFactory.create({ name, _ -> supplied[name] }))
        supplied.values.forEach { it.fill(0) }
        assertEquals(IpadicFixture.expected("日本語と東京"), tokenizer.tokenize("日本語と東京"))
    }

    @Test fun sharedDictionaryHasNoPerCallOrResolverLifetimeState() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val expected = IpadicFixture.expected("東京へ行く。日本語🦄")
            val tokenizer = IpadicFixture.portable
            val jobs = (1..32).map { executor.submit<List<JapaneseToken>> { tokenizer.tokenize("東京へ行く。日本語🦄") } }
            jobs.forEach { assertEquals(expected, it.get()) }
        } finally {
            executor.shutdownNow()
        }
    }
}
