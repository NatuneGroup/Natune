package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** One published release as the host projects it. [notes] is the raw release body, rendered as Markdown. */
data class ChangelogRelease(
    val tag: String,
    val name: String,
    val publishedAt: String?,
    val notes: String?,
)

/** Loading, failure and loaded states are explicit so an empty list is never shown for a failed fetch. */
sealed interface ChangelogState {
    data object Loading : ChangelogState
    data object Failed : ChangelogState
    data class Loaded(val releases: List<ChangelogRelease>) : ChangelogState
}

/**
 * Read-only release notes for one channel. The host owns the fetch and calls [onRetry] on demand. Links in the
 * notes go to [onOpenUrl]; this page never opens a URI itself.
 */
@Composable
fun ChangelogSettings(
    channel: UpdateChannel,
    state: ChangelogState,
    onRetry: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        ChangelogState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ChangelogState.Failed -> Column(
            modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(Res.string.m6_update_status_unavailable))
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.m6_retry)) }
        }
        is ChangelogState.Loaded -> if (state.releases.isEmpty()) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.m6_changelog_empty))
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "channel_subtitle") {
                    Text(
                        stringResource(
                            if (channel == UpdateChannel.NIGHTLY) Res.string.m6_changelog_subtitle_nightly
                            else Res.string.m6_changelog_subtitle_stable,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(state.releases, key = { it.tag }) { release -> ReleaseCard(release, onOpenUrl) }
            }
        }
    }
}

@Composable
private fun ReleaseCard(release: ChangelogRelease, onOpenUrl: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(release.name.ifBlank { release.tag }, style = MaterialTheme.typography.titleMedium)
            release.publishedAt?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            release.notes?.takeIf { it.isNotBlank() }?.let {
                ReleaseNotesBody(markdown = it, onOpenUrl = onOpenUrl)
            }
        }
    }
}

@Composable
private fun ReleaseNotesBody(markdown: String, onOpenUrl: (String) -> Unit) {
    val blocks = remember(markdown) { parseReleaseNotes(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ReleaseNoteBlock.Heading -> Text(
                    text = releaseNoteText(block.spans, linkColor, onOpenUrl),
                    style = if (block.level <= 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is ReleaseNoteBlock.Bullet -> Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                    Text(
                        text = releaseNoteText(block.spans, linkColor, onOpenUrl),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                is ReleaseNoteBlock.Numbered -> Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${block.number}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = releaseNoteText(block.spans, linkColor, onOpenUrl),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                is ReleaseNoteBlock.Paragraph -> Text(
                    text = releaseNoteText(block.spans, linkColor, onOpenUrl),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun releaseNoteText(
    spans: List<ReleaseNoteSpan>,
    linkColor: androidx.compose.ui.graphics.Color,
    onOpenUrl: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        val url = span.url
        when {
            url != null -> withLink(
                LinkAnnotation.Clickable(
                    tag = url,
                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onOpenUrl(url) },
                ),
            ) { append(span.text) }
            span.code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(span.text) }
            span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            else -> append(span.text)
        }
    }
}
