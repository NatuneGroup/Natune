package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentNews
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

typealias NewsImage = @Composable (uri: String, modifier: Modifier) -> Unit
data class NewsUi(
    val items: List<ContentNews>,
    /** False when the build carries a blank news feed URL; a host must explicitly supply availability. */
    val available: Boolean,
    val query: String = "",
    val loading: Boolean = false,
    val failed: Boolean = false,
)
class NewsActions(
    val back: () -> Unit,
    val query: (String) -> Unit,
    val refresh: () -> Unit,
    val open: (String) -> Unit,
    val markRead: (latestTimestamp: Long) -> Unit,
)

@Composable
fun NewsScreen(
    state: NewsUi,
    actions: NewsActions,
    image: NewsImage,
    formatDate: (Long) -> String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val latest = state.items.maxOfOrNull { it.timestamp }
    val markRead by rememberUpdatedState(actions.markRead)
    LaunchedEffect(state.available, state.loading, state.failed, latest) {
        if (state.available && !state.loading && !state.failed && latest != null) markRead(latest)
    }
    val q = state.query.trim()
    val rows = state.items.filter { q.isEmpty() || it.title.contains(q, true) || it.author.contains(q, true) }
        .sortedByDescending { it.timestamp }
    Column(modifier.fillMaxSize()) {
        ManagementHeader(stringResource(Res.string.m4_news), actions.back)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.query, actions.query, Modifier.weight(1f).testTag("news-query"),
                singleLine = true, label = { Text(stringResource(Res.string.m4_search)) })
            TextButton(actions.refresh, enabled = state.available && !state.loading) { Text(stringResource(Res.string.m4_refresh)) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("status") {
                if (!state.available) Text(stringResource(Res.string.m4_news_unavailable),
                    Modifier.padding(16.dp).testTag("news-unavailable"))
                else LoadFeedback(state.loading, state.failed, rows.isEmpty(), actions.refresh)
            }
            if (state.available && !state.loading && !state.failed) {
                itemsIndexed(rows, key = { index, news -> "news-${news.id.length}:${news.id}:$index" }) { _, news ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            NewsMetadata(news, formatDate)
                            if (news.description.isNotBlank()) Text(news.description, style = MaterialTheme.typography.bodyLarge)
                            NewsImages(news.id, news.imageUrls, image)
                            if (news.id.isNotBlank()) {
                                TextButton({ actions.open(news.id) }, Modifier.testTag("news-open-${news.id}")) {
                                    Text(stringResource(Res.string.m4_read_article))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class NewsArticleUi(
    val item: ContentNews?,
    val markdown: String?,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val available: Boolean = true,
)

@Composable
fun NewsArticleScreen(
    state: NewsArticleUi,
    back: () -> Unit,
    retry: () -> Unit,
    openLink: (String) -> Unit,
    image: NewsImage,
    formatDate: (Long) -> String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    Column(modifier.fillMaxSize()) {
        ManagementHeader(stringResource(Res.string.m4_news), back)
        BoxWithConstraints(Modifier.weight(1f)) {
            // Baseline article reading width: centered at 760dp above 840dp, 24dp inset below.
            val readingPadding = if (maxWidth > 840.dp) (maxWidth - 760.dp) / 2 else 24.dp
            LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                state.item?.let { news ->
                    item("metadata") {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                            NewsArticleHeader(news, formatDate, readingPadding)
                        }
                    }
                    if (news.imageUrls.any { it.isNotBlank() }) {
                        // Header images span the full width like the baseline carousel, outside the reading column.
                        item("images") {
                            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                                NewsArticleImages(news.id, news.imageUrls, image)
                            }
                        }
                    }
                }
                item("content") {
                    val textPadding = Modifier.padding(horizontal = readingPadding, vertical = 24.dp)
                    when {
                        !state.available -> Text(stringResource(Res.string.m4_news_unavailable), textPadding)
                        state.loading -> Text(stringResource(Res.string.m4_loading), textPadding)
                        state.failed -> Column(textPadding) {
                            Text(stringResource(Res.string.m4_load_failed))
                            TextButton(retry, Modifier.testTag("article-retry")) { Text(stringResource(Res.string.m4_retry)) }
                        }
                        state.markdown != null -> NewsMarkdown(state.markdown, openLink, image, textPadding)
                        else -> Text(stringResource(Res.string.m4_empty), textPadding)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsMetadata(news: ContentNews, formatDate: (Long) -> String) {
    if (news.important) Text(stringResource(Res.string.m4_important),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(news.title, style = MaterialTheme.typography.headlineMedium)
    Text(news.author, style = MaterialTheme.typography.labelLarge)
    Text(formatDate(news.timestamp), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun NewsArticleHeader(news: ContentNews, formatDate: (Long) -> String, horizontalPadding: Dp) {
    Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(news.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (news.important) AssistChip(
                onClick = {},
                label = { Text(stringResource(Res.string.m4_important), fontWeight = FontWeight.Bold) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                border = null,
                shape = MaterialTheme.shapes.large,
            )
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        stringResource(Res.string.m4_news_author_on_date, news.author, formatDate(news.timestamp)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = null,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

// Feed cards keep this compact pager. The article header uses NewsArticleImages, which follows the baseline carousel.
@Composable
private fun NewsImages(id: String, urls: List<String>, image: NewsImage) {
    val images = urls.filter { it.isNotBlank() }
    if (images.isEmpty()) return
    key(id, images) {
        val pager = rememberPagerState { images.size }
        val scope = rememberCoroutineScope()
        var expanded by rememberSaveable { mutableStateOf(false) }
        HorizontalPager(pager, Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(1.5f)) { index ->
            image(images[index], Modifier.fillMaxSize().clickable { expanded = true }
                .testTag("news-image-$id-$index"))
        }
        if (images.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ scope.launch { pager.scrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
                enabled = pager.currentPage > 0, modifier = Modifier.testTag("news-image-previous-$id")) {
                Text(stringResource(Res.string.m4_previous_image))
            }
            Text(stringResource(Res.string.m4_image_count, pager.currentPage + 1, images.size))
            TextButton({ scope.launch { pager.scrollToPage((pager.currentPage + 1).coerceAtMost(images.lastIndex)) } },
                enabled = pager.currentPage < images.lastIndex, modifier = Modifier.testTag("news-image-next-$id")) {
                Text(stringResource(Res.string.m4_next_image))
            }
        }
        if (expanded) Dialog(onDismissRequest = { expanded = false }) {
            Surface(Modifier.fillMaxWidth().testTag("news-image-dialog"), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    image(images[pager.currentPage], Modifier.fillMaxWidth().aspectRatio(1f))
                    TextButton({ expanded = false }, Modifier.testTag("news-image-close")) { Text(stringResource(Res.string.m4_close)) }
                }
            }
        }
    }
}

/**
 * The article's header images, shown as the baseline article showed them. One picture is a full-width frame inset
 * 24dp with the extra-large corner clip; several pictures form a browse carousel. A tap opens the picture full-screen.
 * The injected [image] draws each picture into the frame it is given, and the frame owns size, clip and tap.
 */
@Composable
private fun NewsArticleImages(id: String, urls: List<String>, image: NewsImage) {
    val images = urls.filter { it.isNotBlank() }
    if (images.isEmpty()) return
    key(id, images) {
        var fullImage by rememberSaveable { mutableStateOf<String?>(null) }
        if (images.size == 1) {
            val url = images.first()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .padding(horizontal = 24.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(role = Role.Image) { fullImage = url }
                    .testTag("news-image-$id-0"),
            ) {
                image(url, Modifier.fillMaxSize())
            }
        } else {
            val carouselState = rememberCarouselState { images.size }
            HorizontalMultiBrowseCarousel(
                state = carouselState,
                preferredItemWidth = 320.dp,
                itemSpacing = 12.dp,
                contentPadding = PaddingValues(horizontal = 24.dp),
                modifier = Modifier.fillMaxWidth().height(320.dp),
            ) { index ->
                val url = images[index]
                Box(
                    Modifier
                        .fillMaxSize()
                        .maskClip(MaterialTheme.shapes.extraLarge)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(role = Role.Image) { fullImage = url }
                        .testTag("news-image-$id-$index"),
                ) {
                    image(url, Modifier.fillMaxSize())
                }
            }
        }
        fullImage?.let { url -> NewsFullImageDialog(url, image) { fullImage = null } }
    }
}

/**
 * The full-screen picture on the baseline black scrim: a tap or the back gesture dismisses it, and the host [image]
 * fits the picture. The close control keeps screen-reader users and the M4 test tag.
 */
@Composable
private fun NewsFullImageDialog(url: String, image: NewsImage, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .clickable(onClick = onDismiss)
                .testTag("news-image-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            image(url, Modifier.fillMaxWidth())
            TextButton(
                onDismiss,
                Modifier.align(Alignment.TopEnd).testTag("news-image-close"),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text(stringResource(Res.string.m4_close))
            }
        }
    }
}
