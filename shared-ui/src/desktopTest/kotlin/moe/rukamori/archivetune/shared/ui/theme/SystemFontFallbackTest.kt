package moe.rukamori.archivetune.shared.ui.theme

import kotlin.test.Test
import kotlin.test.assertTrue

class SystemFontFallbackTest {
    @Test
    fun theReportNamesEveryScriptThatReliesOnSystemFonts() {
        val report = SystemFontFallback.report()

        // Resolved families depend on the host's installed fonts, so the contract is the shape, not a family name.
        listOf("latin=", "cjk-han=", "cjk-hiragana=", "hangul=", "cyrillic=", "arabic=", "devanagari=", "emoji=")
            .forEach { entry -> assertTrue(report.contains(entry), "missing $entry in $report") }
    }
}
