package moe.rukamori.archivetune.shared.data.stream.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsMediaPlaylistTest {
    private val base = "https://media.example/hls/track/playlist.m3u8"

    @Test
    fun parsesClearVodPlaylist() {
        val playlist = HlsMediaPlaylistParser.parse(
            base,
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:40
            #EXTINF:6.006,
            seg040.ts
            #EXTINF:6.006,
            seg041.ts
            #EXTINF:2.0,
            seg042.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        assertEquals(3, playlist.segments.size)
        assertTrue(playlist.keys.isEmpty())
        assertTrue(playlist.initMaps.isEmpty())
        assertEquals("https://media.example/hls/track/seg040.ts", playlist.segments[0].uri)
        assertEquals(40L, playlist.segments[0].mediaSequence)
        assertEquals(42L, playlist.segments[2].mediaSequence)
        assertEquals(-1, playlist.segments[0].keyIndex)
    }

    @Test
    fun parsesAes128WithInitMapByteRangesAndKeyRotation() {
        val playlist = HlsMediaPlaylistParser.parse(
            base,
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k1",IV=0x00000000000000000000000000000009
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:1000@0
            media.fmp4
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:500
            media.fmp4
            #EXT-X-KEY:METHOD=AES-128,URI="/k2.bin"
            #EXTINF:4.0,
            other.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        assertEquals(1, playlist.initMaps.size)
        assertEquals("https://media.example/hls/track/init.mp4", playlist.initMaps[0].uri)
        assertEquals(2, playlist.keys.size)
        assertEquals("https://keys.example/k1", playlist.keys[0].keyUri)
        assertEquals(9, playlist.keys[0].iv?.last()?.toInt())
        assertEquals("https://media.example/k2.bin", playlist.keys[1].keyUri)
        assertEquals(0, playlist.segments[0].keyIndex)
        assertEquals(0, playlist.segments[1].keyIndex)
        assertEquals(1, playlist.segments[2].keyIndex)
        assertEquals(1000L, playlist.segments[0].byteRange?.length)
        assertEquals(500L, playlist.segments[1].byteRange?.length)
        assertNull(playlist.segments[1].byteRange?.offset)
        assertEquals(0, playlist.segments[0].initIndex)
    }

    @Test
    fun methodNoneReturnsToClearSegments() {
        val playlist = HlsMediaPlaylistParser.parse(
            base,
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k1"
            #EXTINF:1.0,
            enc.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:1.0,
            clear.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        assertEquals(0, playlist.segments[0].keyIndex)
        assertEquals(-1, playlist.segments[1].keyIndex)
    }

    @Test
    fun resolvesSegmentForms() {
        assertEquals("https://a.example/x/y.ts", resolveHlsUri("https://a.example/x/list.m3u8", "y.ts"))
        assertEquals("https://a.example/y.ts", resolveHlsUri("https://a.example/x/list.m3u8", "/y.ts"))
        assertEquals("https://b.example/y.ts", resolveHlsUri("https://a.example/x/list.m3u8", "//b.example/y.ts"))
        assertEquals("https://a.example/y.ts", resolveHlsUri("https://a.example/x/list.m3u8?sig=1", "../y.ts"))
        assertNull(resolveHlsUri("https://a.example/x/list.m3u8", "skd://fairplay"))
        assertNull(resolveHlsUri("https://a.example/x/list.m3u8", "data:,AAAA"))
        assertNull(resolveHlsUri("notaurl", "seg.ts"))
    }

    @Test
    fun refusesMasterVariantAndDrmForms() {
        assertFailsWith<HlsPlaylistException> {
            HlsMediaPlaylistParser.parse(base, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nx.m3u8\n")
        }
        assertFailsWith<HlsPlaylistException> {
            HlsMediaPlaylistParser.parse(base, "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"a.m3u8\"\n#EXTINF:1,\nx.ts\n#EXT-X-ENDLIST\n")
        }
        assertFailsWith<HlsPlaylistException> {
            HlsMediaPlaylistParser.parse(base, "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:1,\nx.ts\n#EXT-X-ENDLIST\n")
        }
        assertFailsWith<HlsPlaylistException> {
            HlsMediaPlaylistParser.parse(
                base,
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:1,\nx.ts\n#EXT-X-ENDLIST\n",
            )
        }
        assertFailsWith<HlsPlaylistException> {
            HlsMediaPlaylistParser.parse(
                base,
                "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"k\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\n#EXTINF:1,\nx.ts\n#EXT-X-ENDLIST\n",
            )
        }
    }

    @Test
    fun refusesLiveMalformedAndEmptyDocuments() {
        assertFailsWith<HlsPlaylistException> {
            HlsMediaPlaylistParser.parse(base, "#EXTM3U\n#EXTINF:1,\nx.ts\n")
        }
        assertFailsWith<HlsPlaylistException> {
            HlsMediaPlaylistParser.parse(base, "not a playlist\n")
        }
        assertFailsWith<HlsPlaylistException> {
            HlsMediaPlaylistParser.parse(base, "#EXTM3U\n#EXT-X-ENDLIST\n")
        }
        assertFailsWith<HlsPlaylistException> {
            HlsMediaPlaylistParser.parse(base, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x1234\n#EXTINF:1,\nx.ts\n#EXT-X-ENDLIST\n")
        }
    }

    @Test
    fun mediaSequenceBaseAppliesToFirstSegmentOnly() {
        val playlist = HlsMediaPlaylistParser.parse(
            base,
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:100\n#EXTINF:1,\na.ts\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST\n",
        )
        assertEquals(100L, playlist.segments[0].mediaSequence)
        assertEquals(101L, playlist.segments[1].mediaSequence)
        assertFalse(playlist.segments[0].discontinuity)
    }

    @Test
    fun detectsPlaylistUrls() {
        assertTrue(isHlsPlaylistUrl("https://h.example/a/b.m3u8"))
        assertTrue(isHlsPlaylistUrl("https://h.example/a/b.M3U8?sig=x"))
        assertFalse(isHlsPlaylistUrl("https://h.example/a/b.mp3"))
        assertFalse(isHlsPlaylistUrl("https://h.example/a/m3u8file"))
    }
}
