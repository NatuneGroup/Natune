package moe.rukamori.archivetune.desktop

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
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.navigation.DesktopSearchFocus
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.navigation.ShellTab
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.shortcuts.shortcutTextInput
import moe.rukamori.archivetune.shared.ui.theme.RowHeights
import moe.rukamori.archivetune.shared.ui.theme.Space
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val TopBarHeight = RowHeights.topBar
private val SearchPillHeight = 40.dp
private val SearchFieldMaxWidth = 390.dp

/**
 * The window's persistent top bar: the app name at the left, one centred search pill across the remaining width, and
 * the key chord that focuses it inside the pill. It is host chrome, not a page header, so it sits above the shell on
 * every route, Settings included, and never moves with the page.
 */
@Composable
internal fun DesktopTopBar(
    navigation: ShellNavigation,
    searchFocus: DesktopSearchFocus,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    fun submit() {
        val text = query.trim()
        if (text.isEmpty()) return
        navigation.select(ShellTab.Search, toRoot = true)
        navigation.open(ArchiveRoutes.search(text, ContentProvider.YOUTUBE))
        focusManager.clearFocus()
    }
    // Ctrl+K bumps the counter; the first composition does not steal focus from the page.
    LaunchedEffect(searchFocus.request) {
        if (searchFocus.request == 0) return@LaunchedEffect
        runCatching { focusRequester.requestFocus() }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().height(TopBarHeight).testTag("desktop-top-bar"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
            Text(
                DesktopStrings["app_name"],
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            val pillWidth = minOf(SearchFieldMaxWidth, maxWidth - 220.dp).coerceAtLeast(200.dp)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.align(Alignment.Center).width(pillWidth).height(SearchPillHeight)
                    .testTag("desktop-search-pill"),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(start = 14.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painterResource(Res.drawable.m4_icon_search),
                        contentDescription = stringResource(Res.string.m4_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.weight(1f).focusRequester(focusRequester)
                            .shortcutTextInput()
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.Enter -> { submit(); true }
                                    Key.Escape -> { query = ""; focusManager.clearFocus(); true }
                                    else -> false
                                }
                            }
                            .testTag("desktop-search-field"),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        DesktopStrings["search_placeholder"],
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
                            DesktopStrings["search_shortcut"],
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.75f),
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag("desktop-search-hint"),
                        )
                    }
                }
            }
        }
    }
}
