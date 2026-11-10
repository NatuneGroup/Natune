package moe.rukamori.archivetune.shared.ui.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import moe.rukamori.archivetune.shared.ui.LocalHostSafeArea
import moe.rukamori.archivetune.shared.ui.SafeAreaPadding
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.safeAreaPadding
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** Native iOS Skia images; injected insets do not verify a UIKit window or its gestures. */
@OptIn(ExperimentalTestApi::class, ExperimentalForeignApi::class)
class IosPlatformRenderTest {
    @Test fun capturePhoneScreens() {
        val directory = checkNotNull(getenv("NATUNE_RENDER_DIR")) { "Missing simulator capture directory" }.toKString()
        for (name in AuditScreens) {
            println("NATIVE-RENDER $name")
            runSkikoComposeUiTest(size = Size(1179f, 2556f), density = Density(3f), testTimeout = 2.minutes) {
                mainClock.autoAdvance = false
                setContent {
                    ArchiveTuneTheme(darkTheme = false, disableAnimations = true) {
                        val insets = SafeAreaPadding(top = 59.0, bottom = 34.0)
                        CompositionLocalProvider(
                            LocalAndroidPhonePresentation provides true,
                            LocalDesktopLayout provides false,
                            LocalHostSafeArea provides insets,
                        ) {
                            Surface(Modifier.fillMaxSize().testTag("audit-root"), color = MaterialTheme.colorScheme.background) {
                                if (name in setOf("player", "lyrics", "fullscreen-lyrics", "transport-glyphs", "queue") || name.startsWith("player-style-")) RenderAuditFixture.Content(name, true)
                                else Box(Modifier.fillMaxSize().safeAreaPadding(insets)) { RenderAuditFixture.PhoneShell(name) }
                            }
                        }
                    }
                }
                mainClock.advanceTimeBy(640)
                waitForIdle()
                val bitmap = onNodeWithTag("audit-root").captureToImage()
                val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
                try {
                    val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
                    try { writePng("$directory/$name.png", data.bytes) } finally { data.close() }
                } finally { image.close() }
                println("NATIVE-SAVED $name")
            }
        }
    }

    private fun writePng(path: String, bytes: ByteArray) {
        val file = checkNotNull(fopen(path, "wb")) { "Cannot open $path" }
        try {
            bytes.usePinned { pinned ->
                check(fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file) == bytes.size.toULong())
            }
        } finally { check(fclose(file) == 0) }
    }
}
