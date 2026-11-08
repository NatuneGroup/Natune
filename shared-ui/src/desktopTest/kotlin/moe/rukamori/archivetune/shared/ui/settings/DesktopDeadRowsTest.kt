package moe.rukamori.archivetune.shared.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/**
 * Rows whose only readers are Android (or whose desktop menu refuses the action) must not be
 * offered on a desktop page. The stored preferences remain for Android and for backups; only the
 * dead rows are gated. These are pure row builders, so the desktop call sites that pass desktop =
 * true are covered.
 */
class DesktopDeadRowsTest {
    @Test
    fun downloadsWithoutDesktopKeepEveryPhoneRow() {
        val rows = downloadsSettingsRows(PreferenceSnapshot.EMPTY)
        assertEquals(
            listOf(
                "clear_all_downloads",
                "export_downloaded_songs",
                "auto_download_like",
                "download_source",
                "external_downloader",
                "external_downloader_package",
            ),
            rows.map { it.anchor },
        )
    }

    @Test
    fun desktopDownloadsOmitsTheThreeRowsWithNoDesktopReader() {
        val rows = downloadsSettingsRows(PreferenceSnapshot.EMPTY, desktop = true)
        assertEquals(
            listOf("clear_all_downloads", "export_downloaded_songs", "download_source"),
            rows.map { it.anchor },
        )
        listOf("auto_download_like", "external_downloader", "external_downloader_package")
            .forEach { anchor ->
                assertTrue(
                    rows.none { it.anchor == anchor },
                    "desktop downloads kept dead row $anchor",
                )
            }
    }

    @Test
    fun appearanceExtrasOmitsTheLibraryTagsRowOnDesktop() {
        val phone = appearanceExtrasSettingsRows()
        assertEquals(7, phone.size)
        assertTrue(phone.any { it.anchor == "show_tags_in_library" })

        val desktop = appearanceExtrasSettingsRows(desktop = true)
        assertEquals(6, desktop.size)
        assertTrue(desktop.none { it.anchor == "show_tags_in_library" })
        // The rows the shared library renderer reads survive.
        listOf("show_home_category_chips", "hide_liked_songs_card", "hide_offline_card").forEach {
            anchor ->
            assertTrue(desktop.any { it.anchor == anchor }, "missing shared extras row $anchor")
        }
    }
}
