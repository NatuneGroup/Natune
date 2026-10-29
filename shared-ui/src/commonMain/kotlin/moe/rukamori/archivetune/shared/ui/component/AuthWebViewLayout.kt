package moe.rukamori.archivetune.shared.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Keep a real, bounded browser viewport when the keyboard or a long recovery message reduces space.
 * The host still owns the native view, its appearance, profile and navigation. Already-consumed
 * dialog/host IME insets are not applied twice.
 */
@Composable
fun AuthWebViewLayout(
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
        val headerHeight = maxHeight / 3
        val contentSpacing = minOf(12.dp, maxHeight / 6)
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            // Preserve every recovery action rather than hiding it or measuring the browser to zero.
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = headerHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = header,
            )
            content()
        }
    }
}
