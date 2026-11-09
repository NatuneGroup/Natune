package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.shared.ui.navigation.DesktopSearchFocus
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.shortcuts.shortcutTextInput
import moe.rukamori.archivetune.shared.ui.theme.Space
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The host's own window buttons, drawn inside this title bar. The desktop host supplies them on the
 * platforms where the app paints its caption (Windows' 48 dp title bar, where the buttons must sit
 * inline so Snap Layouts hit-tests them); macOS, Wayland and a system frame leave it null and the
 * bar ends after the rail toggle.
 */
val LocalDesktopWindowControls = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * The window-drag surface for the platforms where the app paints its own caption. The host supplies
 * the pointer handling (drag to move, double click to maximize) and the bar applies it to the empty
 * stretches of the top band only, so a press on the search field, the rail toggle or a window
 * button stays a press.
 */
val LocalDesktopWindowDrag = staticCompositionLocalOf<(Modifier) -> Modifier> { { it } }

/**
 * The window's top band: the app mark and the navigation toggle at the left, one search field
 * across the middle, the settings and account buttons the host binds at the right, and the host's
 * own trailing slot for the window buttons.
 *
 * It is one 56 dp band on every host. A platform that paints its own caption supplies its window
 * buttons through [LocalDesktopWindowControls] and its drag handling through
 * [LocalDesktopWindowDrag] instead of drawing a second strip above this one, so the window's chrome
 * is as tall as the reference app's top bar and no taller.
 *
 * Search lives here on every page, including Settings, because the whole window has one search:
 * entering a query opens the search page with that query and keeps the current page one Back away.
 */
@Composable
fun DesktopTitleBar(
    title: String,
    searchFocus: DesktopSearchFocus,
    railExpanded: Boolean,
    onToggleRail: () -> Unit,
    onSubmitSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    /** The settings destination, or null on a host with no title bar settings entry. */
    onOpenSettings: (() -> Unit)? = null,
    /**
     * The phone top bar's update dot, shown on the settings button when the host has an update to
     * offer.
     */
    settingsBadge: Boolean = false,
    /** The account button and the menu it opens; null hides the account area entirely. */
    profileMenu: DesktopProfileMenu? = null,
) {
    val windowDrag = LocalDesktopWindowDrag.current
    // Read once: the window buttons decide whether the actions fit, and the layout scopes below are
    // nested.
    val windowControls = LocalDesktopWindowControls.current
    val captionChrome = LocalDesktopCaptionChrome.current
    // The Windows host removes its non-client area and asks DWM for Mica/immersive-dark, so its
    // caption band is a
    // tonal step above the page rather than the artwork-tinted flat band the Linux reference build
    // draws. Resolving the
    // role here keeps the bar's onSurface/onSurfaceVariant text at its usual contrast in both light
    // and dark schemes.
    val bandColor =
        when (captionChrome) {
            DesktopCaptionChrome.Windows -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainer
        }
    Surface(
        color = bandColor,
        modifier =
            modifier
                .fillMaxWidth()
                .height(DesktopShellMetrics.titleBarHeight)
                .testTag("desktop-title-bar"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Read here, once: the layout scopes below are nested, and a layout-scope member is not
            // reachable
            // implicitly from inside another one.
            // Metrics and the native hit test take the full window width; the row spends padding once.
            val titleBarWidth = maxWidth
            Row(
                Modifier.fillMaxSize().padding(horizontal = DesktopShellMetrics.titleBarPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesktopShellMetrics.titleBarSearchGap),
            ) {
                Row(
                    // The empty stretch around the mark is the first place a drag can start the
                    // window move.
                    Modifier.weight(1f).fillMaxHeight().then(windowDrag(Modifier)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    // On macOS the native title bar already carries the traffic lights; the mark
                    // still names the app.
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.testTag("desktop-title"),
                    )
                    leading()
                }
                Box(
                    // The band beside the field is the second drag target: the field itself keeps
                    // its own presses.
                    Modifier.weight(2f).fillMaxHeight().then(windowDrag(Modifier)),
                    contentAlignment = Alignment.Center,
                ) {
                    DesktopSearchField(
                        width = desktopSearchFieldWidth(titleBarWidth),
                        focus = searchFocus,
                        onSubmit = onSubmitSearch,
                    )
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DesktopRailToggle(railExpanded, onToggleRail)
                        trailing()
                        // A narrow window drops these rather than clipping them against the
                        // caption: the trailing
                        // column is a quarter of the bar and the host's window buttons end it.
                        if (
                            desktopTitleBarActionsFit(
                                titleBarWidth,
                                hasWindowControls = windowControls != null,
                            )
                        ) {
                            DesktopTitleBarActions(
                                settingsBadge = settingsBadge,
                                onOpenSettings = onOpenSettings,
                                profile = profileMenu,
                            )
                        }
                        windowControls?.invoke()
                    }
                }
            }
        }
    }
}

/**
 * The one search field of the window. Ctrl+K (or the shell's Search shortcut) bumps [focus]; the
 * counter, not a boolean, so a second request while the field is already focused still runs the
 * focus request.
 */
@Composable
private fun DesktopSearchField(
    width: Dp,
    focus: DesktopSearchFocus,
    onSubmit: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    fun submit() {
        val text = query.trim()
        if (text.isEmpty()) return
        onSubmit(text)
        focusManager.clearFocus()
    }
    LaunchedEffect(focus.request) {
        if (focus.request == 0) return@LaunchedEffect
        runCatching { requester.requestFocus() }
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier =
            Modifier.width(width)
                .height(DesktopShellMetrics.searchFieldHeight)
                .testTag("desktop-search-pill"),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = Space.sm, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Icon(
                painter = painterResource(Res.drawable.m4c_icon_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier =
                    Modifier.weight(1f)
                        .focusRequester(requester)
                        .shortcutTextInput()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter -> {
                                    submit()
                                    true
                                }
                                Key.Escape -> {
                                    query = ""
                                    focusManager.clearFocus()
                                    true
                                }
                                else -> false
                            }
                        }
                        .testTag("desktop-search-field"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(Res.string.desktop_shell_search_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isEmpty()) {
                Text(
                    stringResource(Res.string.desktop_shell_search_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier =
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                            .padding(horizontal = Space.xs, vertical = 2.dp)
                            .testTag("desktop-search-hint"),
                )
            }
        }
    }
}
