package moe.rukamori.archivetune

import moe.rukamori.archivetune.constants.InterfaceStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.shared.ui.player.MetrolistPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidHostParityTest {

    @Test
    fun `player styles keep the persisted names in order`() {
        assertEquals(
            listOf(
                "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9",
                "APPLE_MUSIC", "V10", "BITCHORD", "TIKTOK", "SIMPMUSIC",
                "SPATIALFLOW", "LOOPER", "FLAMINGO", "METROLIST_KMP",
            ),
            PlayerDesignStyle.entries.map { it.name },
        )
        assertEquals(PlayerDesignStyle.V4, PlayerDesignStyle.Default)
    }

    @Test
    fun `stored style names decode by enum name and unknown ones fall back to V4`() {
        assertEquals(PlayerDesignStyle.METROLIST_KMP, "METROLIST_KMP".toEnum(defaultValue = PlayerDesignStyle.V4))
        assertEquals(PlayerDesignStyle.V4, "NOT_A_STYLE".toEnum(defaultValue = PlayerDesignStyle.V4))
        assertEquals(MetrolistPresentation.VINTAGE, "VINTAGE".toEnum(defaultValue = MetrolistPresentation.MODERN))
        assertEquals(MetrolistPresentation.MODERN, "NOPE".toEnum(defaultValue = MetrolistPresentation.MODERN))
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
