/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.rukamori.archivetune.shared.domain.lyrics.ai.Sha256

/**
 * The host reads only data entries from its installed pack; it must enforce [Resource.sizeBytes]
 * while decompressing, close its handles, and run this factory off the renderer/startup thread.
 * Missing/wrong-sized/corrupt resources return null. I/O failures and cancellation propagate.
 * Hashes below pin the uncompressed bytes from the same 0.9.0 JAR used by Android.
 */
object KuromojiJapaneseTokenizerFactory {
    const val ARCHIVE_URL = "https://repo.maven.apache.org/maven2/com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar"
    const val ARCHIVE_SIZE_BYTES = 13_343_016
    const val ARCHIVE_SHA256 = "24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6"
    const val RESOURCE_PREFIX = "com/atilika/kuromoji/ipadic/"

    data class Resource(val name: String, val sizeBytes: Int, val sha256: String)
    /** A snapshot: callers cannot mutate the pins used by verification through a JVM collection cast. */
    val resources: List<Resource> get() = pinnedResources.toList()
    private val pinnedResources: List<Resource> = listOf(
        Resource("characterDefinitions.bin", 398718, "01c110da81135a32205da569b0378cab327b804c82341651bc55bd54b9d13370"),
        Resource("connectionCosts.bin", 3463720, "e5901a20a644adb0b1cc6b19029af47492aa51e8221547dbf3b500ce86206929"),
        Resource("doubleArrayTrie.bin", 5649577, "80d1c8ce6000d261351f4798e9bd38bc207b3c3c2a59b6caee63b42629dcb26c"),
        Resource("tokenInfoDictionary.bin", 9411048, "b5bd52a59ebe3901a06ae149637ac57c8f31af79e3f6458b6eeb77d163266464"),
        Resource("tokenInfoFeaturesMap.bin", 8987055, "48fd13fc57f7071cf3a5b7544475e4dc293993666e35129827cc1abcc18c6ac0"),
        Resource("tokenInfoPartOfSpeechMap.bin", 2734, "4a84a0c91ef03dd8f4484318ed5230e0b56baa087460a4dfa27c202ffadd2478"),
        Resource("tokenInfoTargetMap.bin", 5511352, "626185aa6d0aba1760ea23d40a818b48bcefe3250f89823da3bf87aa33d4063d"),
        Resource("unknownDictionary.bin", 2586, "ab1cc944ebc5d38a2e1558c7c09a1b3277f27f3613465c14c49115870d127987"),
    )

    /**
     * Returns an immutable tokenizer only after all eight pinned data entries and a real probe pass.
     * Retain it in the existing language-pack lifecycle; clearing/removing/off must detach it there.
     * No download, settings mutation, locale inference, retry or automatic-install policy lives here.
     */
    suspend fun create(
        readResource: suspend (name: String, maxBytes: Int) -> ByteArray?,
        limits: KuromojiLimits = KuromojiLimits(),
    ): PortableKuromojiJapaneseTokenizer? {
        val context = currentCoroutineContext()
        val data = LinkedHashMap<String, ByteArray>()
        for (resource in pinnedResources) {
            context.ensureActive()
            val supplied = readResource(resource.name, resource.sizeBytes)
            context.ensureActive()
            if (supplied == null || supplied.size != resource.sizeBytes) return null
            val owned = supplied.copyOf()
            val digest = Sha256.hexDigest(owned)
            context.ensureActive()
            if (digest != resource.sha256) return null
            data[resource.name] = owned
        }
        val dictionary = KuromojiDictionary(data) { context.ensureActive() }
        // Readiness must not depend on a caller choosing limits smaller than the three-character probe.
        val probe = PortableKuromojiJapaneseTokenizer(dictionary, KuromojiLimits()).tokenize("日本語") { context.ensureActive() }
        check(probe.joinToString("") { it.surface } == "日本語" && probe.any { it.reading == "ニホンゴ" }) {
            "Japanese dictionary is not usable"
        }
        context.ensureActive()
        return PortableKuromojiJapaneseTokenizer(dictionary, limits)
    }
}
