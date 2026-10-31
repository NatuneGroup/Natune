package moe.rukamori.archivetune.shared.data.download

import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import kotlin.test.*

class DownloadFileNamingTest {
    @Test fun aSeparatorOrDotEntryNeverEscapesTheDownloadRoot() {
        listOf("../etc", "..", ".", "/absolute", "a/b", "a\\b", "", "\u0000").forEach { value ->
            val component = sanitizeDownloadComponent(value)
            assertFalse('/' in component, value)
            assertFalse('\\' in component, value)
            assertNotEquals("..", component, value)
            assertNotEquals(".", component, value)
            assertTrue(component.isNotEmpty(), value)
        }
        assertEquals(".._etc", sanitizeDownloadComponent("../etc"))
        assertEquals("_..", sanitizeDownloadComponent(".."))
        assertEquals("_", sanitizeDownloadComponent(""))
        assertEquals("___", sanitizeDownloadComponent("///"))
    }

    @Test fun everyProviderAndQualityKeepsItsOwnFile() {
        val youtube = downloadRelativePath("youtube", "abc123", AudioSourceType.TIDAL, "LOSSLESS")
        val tidal = downloadRelativePath("tidal", "abc123", AudioSourceType.TIDAL, "LOSSLESS")
        val higher = downloadRelativePath("youtube", "abc123", AudioSourceType.TIDAL, "HI_RES_LOSSLESS")
        assertEquals(listOf("youtube", "tidal", "abc123_LOSSLESS.media"), youtube)
        assertNotEquals(youtube, tidal)
        assertNotEquals(youtube, higher)
        assertEquals(listOf("youtube", "tidal", "abc123_HI_RES_LOSSLESS.media"), higher)
    }

    @Test fun theJoinedPathStaysUnderItsRootAndKeepsTheExtension() {
        assertEquals(
            "/root/downloads/youtube/amazon/x_HD.media",
            downloadAbsolutePath("/root/downloads", downloadRelativePath("youtube", "x", AudioSourceType.AMAZON, "HD")),
        )
        val long = downloadFileName("m".repeat(400), "q".repeat(400))
        assertTrue(long.length <= MAX_DOWNLOAD_COMPONENT_LENGTH)
        assertTrue(long.endsWith(".media"))
        assertTrue(long.startsWith("m".repeat(10)))
    }

    @Test fun anExtensionThatIsNotAPlainNameBecomesAPlainOne() {
        assertEquals("id_q.jpeg", downloadFileName("id", "q", "jpeg"))
        val odd = downloadFileName("id", "q", "../mp3")
        assertFalse('/' in odd)
        assertTrue(odd.startsWith("id_q."))
    }
}
