package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.withEffectiveSongMetadata
import moe.rukamori.archivetune.models.MediaMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ModuleMediaItemUpdateTest {
    private val original = MediaItem.Builder().setMediaId("A").setUri("A").setCustomCacheKey("A")
        .setTag(MediaMetadata("A", "Song", listOf(MediaMetadata.Artist("artist", "Artist")), 180)).build()

    @Test
    fun nameEditsAndArtworkCanUpdateWithoutReplacingTheAudioSource() {
        val edit = original.metadata!!.copy(title = "Edited song", canonicalTitle = "Song")
        val updated = original.withEffectiveSongMetadata(edit)
        assertTrue(moduleMediaItemCanUpdate(original, updated))
        assertTrue(moduleMediaItemCanUpdate(original, original.buildUpon()
            .setTag(original.metadata!!.copy(thumbnailUrl = "https://art/updated")).build()))
    }

    @Test
    fun playbackConfigurationAndRecordingChangesRequireANewSource() {
        val incompatible = listOf(
            original.buildUpon().setMediaId("B").build(),
            original.buildUpon().setUri("different-uri").build(),
            original.buildUpon().setCustomCacheKey("different-cache").build(),
            original.buildUpon().setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(100).build()).build(),
            original.buildUpon().setTag(original.metadata!!.copy(id = "B")).build(),
            original.buildUpon().setTag(original.metadata!!.copy(duration = 240)).build(),
            original.buildUpon().setTag(original.metadata!!.copy(isPodcast = true)).build(),
            original.buildUpon().setTag(original.metadata!!.copy(isMusicVideo = true)).build(),
            original.buildUpon().setTag("opaque tag").build(),
        )
        incompatible.forEach { assertFalse(moduleMediaItemCanUpdate(original, it)) }
    }
}
