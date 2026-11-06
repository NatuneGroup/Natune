package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The desktop heading scale: one page title, one section title, one row title. Every desktop page reads its headings from
 * here instead of picking a Material typography role per screen, so Library, Search, Home and Settings share a rhythm.
 * Phone hosts keep their existing typography and use these only where a layout is explicitly desktop.
 */
object DesktopType {
    /** The one page-title size on desktop: 28 sp, semibold, tight tracking. */
    @Composable
    fun pageTitle(): TextStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    )

    /** The one section-title size on desktop: 22 sp, medium. */
    @Composable
    fun sectionTitle(): TextStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.1).sp,
    )

    /** A list row's headline, 16 sp, so every settings and library row shares one baseline. */
    @Composable
    fun rowTitle(): TextStyle = MaterialTheme.typography.titleMedium.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
    )

    /** Supporting copy under a row title; never below 12 sp, as the desktop legibility floor requires. */
    @Composable
    fun rowBody(): TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
}

/** The page title for a desktop page; [phone] is the existing style every other host keeps. */
@Composable
fun desktopPageTitle(desktop: Boolean, phone: TextStyle = MaterialTheme.typography.headlineLarge): TextStyle =
    if (desktop) DesktopType.pageTitle() else phone
