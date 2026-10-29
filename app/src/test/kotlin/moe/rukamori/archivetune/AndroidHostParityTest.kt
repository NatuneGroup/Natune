package moe.rukamori.archivetune

import moe.rukamori.archivetune.constants.InterfaceStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.extensions.toEnum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidHostParityTest {

    @Test
    fun `player styles keep the seventeen persisted names in order`() {
        assertEquals(
            listOf(
                "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9",
                "APPLE_MUSIC", "V10", "BITCHORD", "TIKTOK", "SIMPMUSIC",
                "SPATIALFLOW", "LOOPER", "FLAMINGO",
            ),
            PlayerDesignStyle.entries.map { it.name },
        )
        assertEquals(PlayerDesignStyle.V4, PlayerDesignStyle.Default)
    }

    @Test
    fun `stored style names this host does not mount fall back to V4`() {
        assertEquals(PlayerDesignStyle.V4, "METROLIST_KMP".toEnum(defaultValue = PlayerDesignStyle.V4))
    }

    @Test
    fun `interface presets retain their names and add an honest mixed indicator`() {
        assertEquals(
            listOf("MATERIAL_EXPRESSIVE", "APPLE_MUSIC", "CUSTOM"),
            InterfaceStyle.entries.map { it.name },
        )
    }

    @Test
    fun `playlist share link is the YouTube URL only when a browse id exists`() {
        val remote = PlaylistEntity(name = "remote", browseId = "PLbpi6ZahtOH5G4AiPqsoUhK8tgctua_iIU")
        val local = PlaylistEntity(name = "local")
        assertEquals(
            "https://music.youtube.com/playlist?list=PLbpi6ZahtOH5G4AiPqsoUhK8tgctua_iIU",
            remote.shareLink,
        )
        assertNull(local.shareLink)
    }
}
