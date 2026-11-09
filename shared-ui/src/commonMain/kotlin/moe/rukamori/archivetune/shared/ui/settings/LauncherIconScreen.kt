package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.foundation.layout.asPaddingValues
import moe.rukamori.archivetune.shared.ui.resources.*
import androidx.compose.material3.IconButton

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LauncherIconScreenContent(
    state: LauncherIconScreenState,
    onNavigateUp: () -> Unit,
    onNavigateHome: () -> Unit,
    onRetry: () -> Unit,
    onSelectIcon: (String) -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onShowSortMenu: () -> Unit,
    onDismissSortMenu: () -> Unit,
    onSortOrderChange: (LauncherIconSortOrder) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(Res.string.m6_app_icon))
                },
                subtitle = {
                    Text(text = stringResource(Res.string.phone_icon_app_icon_subtitle))
                },
                navigationIcon = {
                    SettingsBackButton(
                        onClick = onNavigateUp, onLongClick = onNavigateHome,
                        modifier = Modifier.padding(start = 5.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.m4_icon_arrow_back),
                            contentDescription = stringResource(Res.string.m5_artist_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = Color.Transparent,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        when (state) {
            LauncherIconScreenState.Loading -> {
                IconScreenLoading(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            ,
                )
            }

            LauncherIconScreenState.Empty -> {
                IconScreenMessage(
                    message = stringResource(Res.string.phone_icon_app_icon_empty),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            ,
                )
            }

            is LauncherIconScreenState.Error -> {
                IconScreenMessage(
                    message = stringResource(state.messageResId),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            ,
                    action = onRetry,
                )
            }

            is LauncherIconScreenState.Success -> {
                AppIconList(
                    model = state.model,
                    contentPadding =
                        PaddingValues(
                            start = 16.dp,
                            top = innerPadding.calculateTopPadding() + 8.dp,
                            end = 16.dp,
                            bottom = playerAwareBottomPadding + 24.dp,
                        ),
                    onSelectIcon = onSelectIcon,
                    onOpenAuthorProfile = onOpenAuthorProfile,
                    onSearchQueryChange = onSearchQueryChange,
                    onShowSortMenu = onShowSortMenu,
                    onDismissSortMenu = onDismissSortMenu,
                    onSortOrderChange = onSortOrderChange,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            ,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppIconList(
    model: LauncherIconScreenUiModel,
    contentPadding: PaddingValues,
    onSelectIcon: (String) -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onShowSortMenu: () -> Unit,
    onDismissSortMenu: () -> Unit,
    onSortOrderChange: (LauncherIconSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.selectableGroup(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(
            key = CurrentIconContentKey,
            contentType = CurrentIconContentType,
        ) {
            CurrentIconCard(
                icon = model.selectedIcon,
                modifier =
                    Modifier
                        .widthIn(max = IconListMaxWidth)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
            )
        }
        item(
            key = IconSearchContentKey,
            contentType = IconSearchContentType,
        ) {
            AppIconSearchBar(
                query = model.searchQuery,
                sortOrder = model.sortOrder,
                isSortMenuExpanded = model.isSortMenuExpanded,
                onQueryChange = onSearchQueryChange,
                onShowSortMenu = onShowSortMenu,
                onDismissSortMenu = onDismissSortMenu,
                onSortOrderChange = onSortOrderChange,
                modifier =
                    Modifier
                        .widthIn(max = IconListMaxWidth)
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
            )
        }
        item(
            key = IconSectionHeaderKey,
            contentType = IconSectionHeaderContentType,
        ) {
            IconListHeader(
                iconCount = model.icons.size,
                modifier =
                    Modifier
                        .widthIn(max = IconListMaxWidth)
                        .fillMaxWidth()
                        .padding(
                            start = 4.dp,
                            top = 4.dp,
                            end = 4.dp,
                            bottom = 10.dp,
                        ),
            )
        }
        items(
            count = model.icons.size,
            key = { index -> model.icons[index].id },
            contentType = { AppIconContentType },
        ) { index ->
            val icon = model.icons[index]
            AppIconRow(
                icon = icon,
                index = index,
                count = model.icons.size,
                enabled = model.selectionInProgressId == null,
                isApplying = model.selectionInProgressId == icon.id,
                onClick = onSelectIcon,
                onOpenAuthorProfile = onOpenAuthorProfile,
            )
        }
        if (model.hasCommunityIcons) {
            item(
                key = CommunityNoticeContentKey,
                contentType = CommunityNoticeContentType,
            ) {
                CommunityIconNotice(
                    modifier =
                        Modifier
                            .widthIn(max = IconListMaxWidth)
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppIconSearchBar(
    query: String,
    sortOrder: LauncherIconSortOrder,
    isSortMenuExpanded: Boolean,
    onQueryChange: (String) -> Unit,
    onShowSortMenu: () -> Unit,
    onDismissSortMenu: () -> Unit,
    onSortOrderChange: (LauncherIconSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val submitSearch =
        remember(keyboardController) {
            { _: String ->
                keyboardController?.hide()
                Unit
            }
        }
    val clearSearch = remember(onQueryChange) { { onQueryChange("") } }

    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = submitSearch,
                expanded = false,
                onExpandedChange = {},
                placeholder = {
                    Text(text = stringResource(Res.string.m6_search))
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_search),
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            MaterialIconButton(onClick = clearSearch) {
                                Icon(
                                    painter = painterResource(Res.drawable.m4_icon_close),
                                    contentDescription = stringResource(Res.string.m6_crash_logs_confirm_clear),
                                )
                            }
                        }
                        Box {
                            MaterialIconButton(onClick = onShowSortMenu) {
                                Icon(
                                    painter = painterResource(Res.drawable.phone_reference_filter_alt),
                                    contentDescription = null,
                                )
                            }
                            DropdownMenu(
                                expanded = isSortMenuExpanded,
                                onDismissRequest = onDismissSortMenu,
                                modifier = Modifier.widthIn(min = 184.dp),
                            ) {
                                LauncherIconSortOrder.entries.forEach { order ->
                                    val selectOrder =
                                        remember(order, onSortOrderChange) {
                                            { onSortOrderChange(order) }
                                        }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text =
                                                    stringResource(
                                                        when (order) {
                                                            LauncherIconSortOrder.NEW_ADDED -> {
                                                                Res.string.m4_recently_added
                                                            }

                                                            LauncherIconSortOrder.ALPHABETICAL -> {
                                                                Res.string.phone_icon_sort_a_to_z
                                                            }
                                                        },
                                                    ),
                                            )
                                        },
                                        trailingIcon = {
                                            Icon(
                                                painter =
                                                    painterResource(
                                                        if (sortOrder == order) {
                                                            Res.drawable.m6_icon_radio_checked
                                                        } else {
                                                            Res.drawable.m6_icon_radio_unchecked
                                                        },
                                                    ),
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = selectOrder,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier,
        windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) {}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CurrentIconCard(
    icon: LauncherIconUiModel,
    modifier: Modifier = Modifier,
) {
    val name = appIconName(icon)

    ElevatedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AppIconPreview(
                icon = icon,
                size = 88.dp,
                imagePadding = if (icon.isDefault) 6.dp else 0.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.phone_icon_app_icon_current),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (icon.isDefault) {
                    Text(
                        text = stringResource(Res.string.phone_icon_app_icon_builtin_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.phone_icon_app_icon_identifier, icon.id),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IconListHeader(
    iconCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.phone_icon_app_icon_available),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = pluralStringResource(Res.plurals.phone_icon_count, iconCount, iconCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppIconRow(
    icon: LauncherIconUiModel,
    index: Int,
    count: Int,
    enabled: Boolean,
    isApplying: Boolean,
    onClick: (String) -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
) {
    val name = appIconName(icon)
    val author = icon.author
    val select = remember(icon.id, onClick) { { onClick(icon.id) } }
    val rowShapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val selectedBorderColor = MaterialTheme.colorScheme.primary
    val selectedBorderShape = rowShapes.selectedShape
    val selectedBorder =
        remember(icon.isSelected, selectedBorderColor, selectedBorderShape) {
            if (icon.isSelected) {
                Modifier.border(
                    width = 1.dp,
                    color = selectedBorderColor,
                    shape = selectedBorderShape,
                )
            } else {
                Modifier
            }
        }

    SegmentedListItem(
        selected = icon.isSelected,
        onClick = select,
        enabled = enabled && icon.supported,
        shapes = rowShapes,
        modifier =
            Modifier
                .widthIn(max = IconListMaxWidth)
                .fillMaxWidth()
                .heightIn(min = 104.dp)
                .then(selectedBorder),
        colors =
            ListItemDefaults.segmentedColors(
                containerColor =
                    if (icon.isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        leadingContent = {
            AppIconPreview(
                icon = icon,
                size = 72.dp,
                imagePadding = if (icon.isDefault) 4.dp else 0.dp,
            )
        },
        overlineContent = {
            IconTypeBadge(
                isDefault = icon.isDefault,
            )
        },
        supportingContent =
            {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (icon.isDefault) {
                        Text(
                            text = stringResource(Res.string.phone_icon_app_icon_builtin_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.phone_icon_app_icon_identifier, icon.id),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (!icon.supported) Text(stringResource(Res.string.phone_not_available_on_ios), style = MaterialTheme.typography.bodySmall)
                    if (author != null) {
                        AuthorAttribution(
                            icon = icon,
                            onOpenAuthorProfile = onOpenAuthorProfile,
                        )
                    }
                }
            },
        trailingContent = {
            if (isApplying) {
                LoadingIndicator(modifier = Modifier.size(32.dp))
            } else {
                RadioButton(
                    selected = icon.isSelected,
                    onClick = null,
                    enabled = enabled,
                )
            }
        },
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IconTypeBadge(
    isDefault: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isDefault) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        }
    val contentColor =
        if (isDefault) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter =
                    painterResource(
                        if (isDefault) {
                            Res.drawable.phone_reference_auto_awesome
                        } else {
                            Res.drawable.m4hv_icon_person
                        },
                    ),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text =
                    stringResource(
                        if (isDefault) {
                            Res.string.m6_language_pack_built_in
                        } else {
                            Res.string.phone_icon_app_icon_community
                        },
                    ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AuthorAttribution(
    icon: LauncherIconUiModel,
    onOpenAuthorProfile: (String) -> Unit,
) {
    val author = icon.author ?: return
    val githubAuthorUrl = icon.githubAuthorUrl
    if (githubAuthorUrl != null) {
        val openProfile =
            remember(icon.id, onOpenAuthorProfile) {
                { onOpenAuthorProfile(icon.id) }
            }
        val linkedAuthor =
            remember(author, githubAuthorUrl, openProfile) {
                buildAnnotatedString {
                    withLink(
                        LinkAnnotation.Clickable(githubAuthorUrl) { openProfile() },
                    ) {
                        append(author)
                    }
                }
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.m6_icon_github),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = linkedAuthor,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Text(
            text = author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CommunityIconNotice(modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.primary,
                border =
                    BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(Res.drawable.phone_reference_auto_awesome),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Text(
                text = stringResource(Res.string.phone_icon_app_icon_community_notice),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppIconPreview(
    icon: LauncherIconUiModel,
    size: Dp,
    imagePadding: Dp,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(icon.previewDrawableResId),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(imagePadding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun appIconName(icon: LauncherIconUiModel): String =
    icon.nameResId?.let { nameResId ->
        stringResource(nameResId)
    } ?: icon.name.orEmpty()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IconScreenLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator(modifier = Modifier.size(48.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IconScreenMessage(
    message: String,
    modifier: Modifier = Modifier,
    action: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier.padding(
                horizontal = 16.dp,
                vertical = 24.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            FilledTonalButton(
                onClick = action,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(Res.string.m12_retry))
            }
        }
    }
}


@Composable
private fun Modifier: Modifier = this

private const val AppIconContentType = "app_icon"
private const val CurrentIconContentKey = "current_icon"
private const val CurrentIconContentType = "current_icon_summary"
private const val IconSearchContentKey = "icon_search"
private const val IconSearchContentType = "icon_search"
private const val IconSectionHeaderKey = "icon_section_header"
private const val IconSectionHeaderContentType = "icon_section_header"
private const val CommunityNoticeContentKey = "community_icon_notice"
private const val CommunityNoticeContentType = "community_icon_notice"
private val IconListMaxWidth = 720.dp
