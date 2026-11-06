package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.navigation.desktopSettingsNavWidth
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.theme.DesktopType
import moe.rukamori.archivetune.shared.ui.theme.IconSizes
import moe.rukamori.archivetune.shared.ui.theme.RowHeights
import moe.rukamori.archivetune.shared.ui.theme.Space
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private data class SettingsNavItem(
    val route: String,
    val title: String,
    val description: String?,
    val group: SettingsRow.Header?,
)

/**
 * The desktop settings shell: a searchable category pane beside the selected page. The categories are the same rows the
 * single-column settings home renders, so an entry cannot exist in one and be missing in the other. Selection rewrites
 * the current settings route instead of pushing a page, which keeps one settings entry on the navigation stack.
 *
 * [selectedRoute] is the route the page currently shows; [detail] is that page. Phone hosts never compose this.
 */
@Composable
fun SettingsTwoPane(
    selectedRoute: String?,
    rows: List<SettingsRow>,
    onSelect: (String) -> Unit,
    fallbackTitle: String,
    modifier: Modifier = Modifier,
    detail: @Composable () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val query = filter.trim().lowercase()
    var group: SettingsRow.Header? = null
    val navItems = mutableListOf<SettingsNavItem>()
    for (row in rows) {
        when {
            row is SettingsRow.Header -> group = row
            // A hidden row (a developer page) is listed only while the user searches for it, so it stays reachable
            // without being offered to every user.
            row is SettingsRow.Action && row.navRoute != null && (!row.hidden || query.isNotEmpty()) -> {
                val descriptionRes = row.description
                val description = if (descriptionRes != null) stringResource(descriptionRes) else null
                navItems.add(
                    SettingsNavItem(
                        route = row.navRoute,
                        title = stringResource(row.title),
                        description = description,
                        group = group,
                    ),
                )
            }
        }
    }
    val visible = if (query.isEmpty()) {
        navItems
    } else {
        navItems.filter { item ->
            item.title.lowercase().contains(query) || item.description?.lowercase()?.contains(query) == true
        }
    }
    val selectedTitle = navItems.firstOrNull { it.route == selectedRoute }?.title
        ?: selectedRoute?.let { route ->
            navItems.filter { route.startsWith(it.route + "/") }.maxByOrNull { it.route.length }?.title
        }
        ?: fallbackTitle
    BoxWithConstraints(modifier.fillMaxSize()) {
        val navWidth = desktopSettingsNavWidth(maxWidth)
        Row(Modifier.fillMaxSize()) {
            SettingsNavigationPane(
                items = visible,
                selectedRoute = selectedRoute,
                width = navWidth,
                filter = filter,
                onFilter = { filter = it },
                onClearFilter = { filter = ""; focusManager.clearFocus() },
                onSelect = onSelect,
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Space.md, end = Space.md, top = Space.md, bottom = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedTitle,
                        style = DesktopType.pageTitle(),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("settings-detail-title"),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) { detail() }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationPane(
    items: List<SettingsNavItem>,
    selectedRoute: String?,
    width: Dp,
    filter: String,
    onFilter: (String) -> Unit,
    onClearFilter: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(
        Modifier.width(width).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag("settings-two-pane"),
    ) {
        SettingsSearchField(
            value = filter,
            onValueChange = onFilter,
            onClear = onClearFilter,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.xs),
        )
        var lastGroup: String? = null
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.xs)) {
            items.forEach { item ->
                val header = item.group
                if (header != null && header.anchor != lastGroup) {
                    lastGroup = header.anchor
                    item(key = "header-${header.anchor}") {
                        Text(
                            stringResource(header.title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = Space.xs, top = Space.sm, bottom = Space.xxs),
                        )
                    }
                }
                item(key = item.route) {
                    SettingsNavRow(item, item.route == selectedRoute) { onSelect(item.route) }
                }
            }
            if (items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(Res.string.m6_settings_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Space.sm).testTag("settings-nav-empty"),
                    )
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(Space.sm)) }
        }
    }
}

@Composable
private fun SettingsNavRow(item: SettingsNavItem, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = RowHeights.setting)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.xs, vertical = Space.xs)
            .testTag("settings-nav-${item.route}"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.description?.let { description ->
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.height(40.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = Space.xs, end = Space.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Icon(
                painterResource(Res.drawable.m4_icon_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSizes.small),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onClear()
                            true
                        } else {
                            false
                        }
                    }
                    .testTag("settings-search-field"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                stringResource(Res.string.m6_settings_search_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp).testTag("settings-search-clear")) {
                    Icon(
                        painterResource(Res.drawable.m4_icon_close),
                        contentDescription = stringResource(Res.string.m4_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(IconSizes.small),
                    )
                }
            }
        }
    }
}
