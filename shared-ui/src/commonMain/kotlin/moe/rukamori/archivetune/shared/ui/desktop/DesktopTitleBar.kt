package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
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
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.shortcuts.shortcutTextInput
import moe.rukamori.archivetune.shared.ui.theme.Space
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The host's own window buttons, drawn inside this title bar. The desktop host supplies them on the platforms where the
 * app paints its caption (Windows' 48 dp title bar, where the buttons must sit inline so Snap Layouts hit-tests them);
 * macOS, Wayland and a system frame leave it null and the bar ends after the rail toggle.
 */
val LocalDesktopWindowControls = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * The window's top strip: the app mark and the navigation toggle at the left, one search field across the middle and
 * the host's own trailing slot for the window buttons at the right.
 *
 * Search lives here on every page, including Settings, because the whole window has one search: entering a query
 * opens the search page with that query and keeps the current page one Back away. The host owns the window buttons,
 * so this bar is also what a Windows custom frame and a Linux caption share.
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
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .height(DesktopShellMetrics.titleBarHeight)
            .testTag("desktop-title-bar"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = DesktopShellMetrics.titleBarPadding)) {
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesktopShellMetrics.titleBarSearchGap),
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    // On macOS the native title bar already carries the traffic lights; the mark still names the app.
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.testTag("desktop-title"),
                    )
                    leading()
                }
                Box(Modifier.weight(2f), contentAlignment = Alignment.Center) {
                    DesktopSearchField(
                        width = desktopSearchFieldWidth(maxWidth),
                        focus = searchFocus,
                        onSubmit = onSubmitSearch,
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DesktopRailToggle(railExpanded, onToggleRail)
                        trailing()
                        LocalDesktopWindowControls.current?.invoke()
                    }
                }
            }
        }
    }
}

/**
 * The one search field of the window. Ctrl+K (or the shell's Search shortcut) bumps [focus]; the counter, not a
 * boolean, so a second request while the field is already focused still runs the focus request.
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
        modifier = Modifier
            .width(width)
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
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier
                    .weight(1f)
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
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                        .padding(horizontal = Space.xs, vertical = 2.dp)
                        .testTag("desktop-search-hint"),
                )
            }
        }
    }
}
