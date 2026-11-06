package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The desktop spacing scale. New chrome uses these tokens instead of raw dp values, so the top bar, sidebar, settings
 * panes and player bar keep one gutter rhythm. Values match the accepted M3 spacing steps (4/8/16/24/32).
 */
object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 16.dp
    val md = 24.dp
    val lg = 32.dp
}

/** Standard interactive heights, so a row on every desktop list is the same size. */
object RowHeights {
    val navigation = 48.dp
    val list = 56.dp
    val setting = 56.dp
    val topBar = 56.dp
    val playerBar = 132.dp
}

/** The three icon sizes the desktop chrome draws; text-adjacent glyphs never fall below the 18 dp compact size. */
object IconSizes {
    val small = 18.dp
    val medium = 20.dp
    val large = 24.dp
}
