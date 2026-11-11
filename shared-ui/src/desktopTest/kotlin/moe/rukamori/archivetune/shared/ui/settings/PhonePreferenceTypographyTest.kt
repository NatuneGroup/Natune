package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.marquee.SettingsMarqueeText
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PhonePreferenceTypographyTest {
    // Android requests Bold for preference titles and draws static descriptions as one ellipsized line.
    @Test fun titlesStayBoldAndStaticPhoneDescriptionsEllipsize() = runComposeUiTest {
        var root by mutableStateOf(true)
        val description = "A long settings description that must be truncated inside the phone row"
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPhoneSettingsRoot provides root) {
                    Box(Modifier.requiredSize(320.dp, 100.dp)) {
                        PhonePreferenceEntry({ Text("Title") },
                            { SettingsMarqueeText(description, active = false, fade = false) }, {}, null, 0, 1, true)
                    }
                }
            }
        }
        fun layout(text: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
            return results.single()
        }
        assertEquals(FontWeight.Bold, layout("Title").layoutInput.style.fontWeight)
        // The description must end before its last character: a fade or an unbounded line would show all of it.
        assertTrue(layout(description).getLineEnd(0, visibleEnd = true) < description.length)
        runOnIdle { root = false }
        assertEquals(FontWeight.Bold, layout("Title").layoutInput.style.fontWeight)
    }
}
