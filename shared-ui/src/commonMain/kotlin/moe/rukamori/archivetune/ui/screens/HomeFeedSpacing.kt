package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val HomeSectionSpacing = 18.dp

/** The fixed gap between home shelves, keyed and typed so list state survives feed swaps. */
internal fun LazyListScope.rukamoriSectionSpacer(key: String) {
    item(
        key = "home_section_spacer_$key",
        contentType = "section_spacer",
    ) {
        Spacer(Modifier.height(HomeSectionSpacing))
    }
}
