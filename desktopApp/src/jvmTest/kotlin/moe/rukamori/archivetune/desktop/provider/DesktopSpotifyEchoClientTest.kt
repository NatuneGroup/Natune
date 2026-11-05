package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*
import moe.rukamori.archivetune.spotify.echo.SpotifyPlayPlayRecordDecoder

class DesktopSpotifyEchoClientTest {
    private val id = "s".repeat(22)
    private val file = ByteArray(20) { (it + 1).toByte() }
    private val obfuscated = ByteArray(16) { it.toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun varint(value: Int): ByteArray {
        var rest = value
        val out = mutableListOf<Byte>()
        while (rest >= 128) { out += ((rest and 127) or 128).toByte(); rest = rest ushr 7 }
        out += rest.toByte()
        return out.toByteArray()
    }
    private fun field(number: Int, bytes: ByteArray) = varint((number shl 3) or 2) + varint(bytes.size) + bytes
    private fun number(field: Int, value: Int) = varint(field shl 3) + varint(value)
    private fun metadata(): ByteArray {
        val audioFile = field(1, file) + number(2, 2)
        val track = field(12, audioFile)
        return field(2, number(2, 10) + field(3, field(3, field(2, track))))
    }

    @Test fun retainedMetadataStorageAndPlayPlayProduceOnlyAnAccountBoundNativeRecipe() = runBlocking<Unit> {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { call ->
            echoAuthReply(call.url.encodedPath)?.let { return@MockEngine respond(it) }
            assertEquals("Bearer stream-bearer", call.headers["Authorization"])
            assertEquals("stream-client-token", call.headers["client-token"])
            assertNull(call.headers["Cookie"])
            paths += call.url.encodedPath
            when {
                call.url.encodedPath.startsWith("/v1/tracks/") ->
                    respond("""{"id":"$id","name":"Song","artists":[{"name":"Artist"}],"album":{"name":"Album"},"duration_ms":180000}""")
                call.url.encodedPath.contains("extended-metadata") -> respond(metadata())
                call.url.encodedPath.contains("storage-resolve") -> {
                    assertTrue(call.url.encodedPath.endsWith("/2/${file.hex()}"))
                    respond("""{"cdnurl":["http://insecure.invalid/audio","https://cdn.invalid/full.ogg"]}""")
                }
                call.url.encodedPath.contains("/playplay/") -> respond(field(1, obfuscated))
                else -> error("unexpected protocol endpoint")
            }
        })
        try {
            val configuration = echoConfiguration()
            val client = DesktopSpotifyEchoClient(http, configuration)
            val request = AudioResolveRequest("song", TrackMatching.Target("Song", listOf("Artist"), null, 180_000),
                directIds = mapOf(AudioSourceType.SPOTIFY to id))
            val native = assertIs<PreparedAudio.Native>(client.resolve("stream-cookie", request, "stream-scope") { true })
            val recipe = assertIs<NativeAudioRecipe.SpotifyPlayPlay>(native.recipe)
            assertEquals("stream-scope", recipe.accountScope)
            assertEquals(recipe.accountScope, native.stream.accountScope)
            assertEquals("https://cdn.invalid/full.ogg", recipe.url)
            assertFalse(recipe.flac)
            assertEquals(SpotifyPlayPlayRecordDecoder.decodeObfuscatedKey(obfuscated).hex(), recipe.key.reveal())
            assertEquals(configuration.audioIv, recipe.iv.reveal())
            assertTrue(native.stream.trustedDirectId)
            assertEquals(4, paths.size)
            client.close()
        } finally { http.close() }
    }

    @Test fun searchMetadataUsesTheStreamingProviderAndRejectsUnidentifiedOrForeignIds() {
        fun row(value: String) = Json.parseToJsonElement(value).jsonObject
        val track = assertNotNull(DesktopSpotifyEchoClient.parseGraphQl(row(
            """{"name":"Song","artists":{"items":[{"profile":{"name":"Artist"}}]},"duration":{"totalMilliseconds":180000},"albumOfTrack":{"name":"Album"}}"""),
            "spotify:track:$id"))
        assertEquals(ContentProvider.SPOTIFY_STREAMING.id, track.item.ref.provider)
        assertEquals(listOf("Artist"), track.artists)
        assertEquals("Album", track.album)
        assertNull(DesktopSpotifyEchoClient.parseRest(row("""{"id":"$id","name":"Song"}""")))
        assertNull(DesktopSpotifyEchoClient.parseRest(row("""{"id":"foreign-id","name":"Song","artists":[{"name":"Artist"}]}""")))
    }
}
