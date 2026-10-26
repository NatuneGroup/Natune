package moe.rukamori.archivetune.shared.data.provider

import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.data.download.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

class ProviderPreparationTest {
    @Test fun realJvmSaavnDecoderUsesInjectedKeyAndNeverRequiresBundledMaterial() {
        val key = "fixture1".toByteArray()
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        val expected = "https://audio.invalid/song_320.mp4"
        val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(expected.toByteArray()))
        val decoder = JvmJioSaavnUrlDecoder(key)
        key.fill(0) // constructor defensively owns its input
        assertEquals(expected, decoder.decode(encoded))
        assertFailsWith<ProviderProtocolFailure> { decoder.decode("malformed") }
    }
    @Test fun realJvmMd5MatchesPublicKnownVectors() {
        val digest = JvmQobuzRequestDigest()
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", digest.md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", digest.md5Hex("abc"))
    }
    @Test fun typedDownloaderRefusesCiphertextManifestAndSourceMismatchBeforeAnyIo() = runTest {
        val root = Files.createTempDirectory("m7-download-preparation")
        val client = HttpClient(MockEngine { error("must not fetch unprepared media") })
        try {
            val downloader = JvmDownloadRepository(root, client)
            val stream = DirectStream("https://audio.invalid/encrypted", "audio/flac", "flac", null, "Deezer", AudioSourceType.DEEZER)
            val key = DownloadKey("media", AudioSourceType.DEEZER, "FLAC")
            assertFailsWith<IllegalArgumentException> {
                downloader.download(key, PreparedAudio.DeezerEncrypted(stream, "7", "personal"))
            }
            val track = ProviderTrack(ContentItem(ContentRef("tidal", ContentKind.SONG, "7"), "Song"), emptyList(), null, null, "{}")
            assertFailsWith<IllegalArgumentException> {
                downloader.download(key.copy(source = AudioSourceType.TIDAL), PreparedAudio.TidalManifest("<MPD/>", track, "LOSSLESS", true))
            }
            assertFailsWith<IllegalArgumentException> {
                downloader.download(key.copy(source = AudioSourceType.QOBUZ), PreparedAudio.Progressive(stream))
            }
            assertEquals(0, Files.list(root).use { it.count() })
        } finally { client.close(); Files.delete(root) }
    }
}
