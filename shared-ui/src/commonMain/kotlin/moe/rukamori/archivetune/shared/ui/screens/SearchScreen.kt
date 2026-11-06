package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.DisableAnimationsKey
import moe.rukamori.archivetune.shared.domain.settings.HideSearchChromeWhileScrollingKey
import moe.rukamori.archivetune.shared.domain.settings.SearchBarPositionKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.appleMusicSearchBar
import moe.rukamori.archivetune.shared.ui.navigation.LocalNavigationBarHiddenByScroll
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.navigation.NavigationBarAnimationSpec
import moe.rukamori.archivetune.shared.ui.navigation.NavigationBarHeight
import moe.rukamori.archivetune.shared.ui.navigation.PillRole
import moe.rukamori.archivetune.shared.ui.navigation.rememberPillStyle
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.shortcuts.shortcutTextInput
import moe.rukamori.archivetune.shared.ui.theme.desktopPageTitle
import org.jetbrains.compose.resources.stringResource

enum class SearchScope { Online, Local }

private val SearchHorizontalPadding = 24.dp
private val ExpressiveSearchBarHeight = 58.dp
private val AppleSearchBarHeight = 48.dp
private val BottomSearchPillMargin = 12.dp
private const val SearchChromeScrollThreshold = 6f

@Composable
fun SearchScreen(
    query: String,
    scope: SearchScope,
    onQuery: (String) -> Unit,
    onScope: (SearchScope) -> Unit,
    onSubmit: () -> Unit,
    onVoice: (() -> Unit)?,
    onRecognition: (() -> Unit)?,
    state: PagedContentState,
    actions: ContentActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    preferences: PreferenceSnapshot = PreferenceSnapshot.EMPTY,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    /**
     * A search shortcut navigated here and asks the field to take focus. The screen consumes the request once, so a
     * later tab visit leaves focus where the user put it.
     */
    focusRequested: Boolean = false,
    onFocusConsumed: () -> Unit = {},
) {
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(focusRequested) {
        if (!focusRequested) return@LaunchedEffect
        // The request arrives with the navigation that composes this screen. A request on a node that is not placed yet
        // fails, and that failure must neither take the window down nor stay pending for the next visit.
        try { fieldFocus.requestFocus() } catch (_: IllegalStateException) { }
        onFocusConsumed()
    }
    val appleMusicStyle = preferences.appleMusicSearchBar()
    val pill = rememberPillStyle(preferences)
    val searchBarAtBottom = preferences[SearchBarPositionKey] == "BOTTOM"
    val hideSearchBarWhileScrolling = preferences[HideSearchChromeWhileScrollingKey, false]
    val bottomSearchChromeSpace =
        (if (appleMusicStyle) AppleSearchBarHeight else ExpressiveSearchBarHeight) + BottomSearchPillMargin * 2
    var searchBarScrolledAway by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val listState = rememberLazyListState()
    val searchBarScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (available.y < -SearchChromeScrollThreshold) {
                        searchBarScrolledAway = true
                    } else if (available.y > SearchChromeScrollThreshold) {
                        searchBarScrolledAway = false
                    }
                    return Offset.Zero
                }
            }
        }
    val showBottomSearchBar =
        !hideSearchBarWhileScrolling ||
            !searchBarScrolledAway ||
            imeVisible ||
            !listState.canScrollBackward
    // The bar slides away with the navigation bar, so the pill follows it down unless the IME needs it.
    val followHiddenNavigationBar = LocalNavigationBarHiddenByScroll.current && !imeVisible
    val animationsOn = !preferences[DisableAnimationsKey, false]
    val navigationBarLowering by animateDpAsState(
        targetValue = if (followHiddenNavigationBar) NavigationBarHeight + pill.bottomInset else 0.dp,
        animationSpec = if (animationsOn) NavigationBarAnimationSpec else snap(),
        label = "bottomSearchPillLowering",
    )

    Box(
        modifier
            .fillMaxSize()
            .then(if (searchBarAtBottom && hideSearchBarWhileScrolling) Modifier.nestedScroll(searchBarScrollConnection) else Modifier)
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!searchBarAtBottom) {
                Text(stringResource(Res.string.m4_search), style = desktopPageTitle(LocalDesktopLayout.current),
                    modifier = Modifier.padding(16.dp))
                SearchField(query, onQuery, onSubmit, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    focusRequester = fieldFocus)
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(scope == SearchScope.Online, { onScope(SearchScope.Online) },
                    label = { Text(stringResource(Res.string.m4_online)) })
                FilterChip(scope == SearchScope.Local, { onScope(SearchScope.Local) },
                    label = { Text(stringResource(Res.string.m4_local)) })
                TextButton(onSubmit, enabled = query.isNotBlank(), modifier = Modifier.testTag("search-submit")) {
                    Text(stringResource(Res.string.m4_search))
                }
            }
            Row(Modifier.padding(horizontal = 16.dp)) {
                onVoice?.let { TextButton(it) { Text(stringResource(Res.string.m4_voice_search)) } }
                onRecognition?.let { TextButton(it) { Text(stringResource(Res.string.m4_recognize)) } }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding(),
                    start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                    bottom = contentPadding.calculateBottomPadding() + if (searchBarAtBottom) bottomSearchChromeSpace else 0.dp,
                ),
            ) { contentRows(state, actions, artwork) }
        }

        if (searchBarAtBottom) {
            AnimatedVisibility(
                visible = showBottomSearchBar,
                enter =
                    slideInVertically(
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        initialOffsetY = { it },
                    ) + fadeIn(),
                exit =
                    slideOutVertically(
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        targetOffsetY = { it },
                    ) + fadeOut(),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { translationY = navigationBarLowering.toPx() }
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.union(WindowInsets.ime)
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        ),
            ) {
                // The bottom search pill takes the shared pill geometry but the raised tone: it sits inside the
                // content the backdrops record, so it cannot sample them without rendering recursively.
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = pill.horizontalInset, vertical = BottomSearchPillMargin)
                            .widthIn(max = pill.maxWidth),
                    shape = pill.shape(PillRole.STANDALONE),
                    color = pill.raisedContainerColor,
                    contentColor = pill.contentColor,
                    shadowElevation = pill.shadowElevation,
                ) {
                    SearchField(
                        query = query,
                        onQuery = onQuery,
                        onSubmit = onSubmit,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SearchHorizontalPadding / 2, vertical = 4.dp)
                            .testTag("search-query-bottom"),
                        focusRequester = fieldFocus,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester,
) {
    // Typing here must never reach the transport keys, so the field reports its focus to the root's counter.
    OutlinedTextField(query, onQuery,
        modifier = modifier.shortcutTextInput().testTag("search-query").focusRequester(focusRequester), singleLine = true,
        label = { Text(stringResource(Res.string.m4_search_query)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSubmit() }))
}
