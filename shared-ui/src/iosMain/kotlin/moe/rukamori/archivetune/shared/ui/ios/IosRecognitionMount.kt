@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.shared.data.platform.ios.IosRecognitionCapture
import moe.rukamori.archivetune.shared.data.platform.ios.IosRecognitionSignature
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.data.recognition.MusicRecognitionOwner
import moe.rukamori.archivetune.shared.data.recognition.ShazamRecognitionMatcher
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionClock
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionDiagnostics
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionSignaturePort
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.screens.MusicRecognitionDetailsActions
import moe.rukamori.archivetune.shared.ui.screens.RecognitionArtwork
import moe.rukamori.archivetune.shared.ui.screens.recognition.RecognitionTimeFormatter
import moe.rukamori.archivetune.shared.ui.screens.recognition.toMusicRecognitionActions
import moe.rukamori.archivetune.shared.ui.screens.recognition.toMusicRecognitionUi
import moe.rukamori.archivetune.shared.ui.screens.recognition.toRecognizedTrackUi
import moe.rukamori.archivetune.shared.ui.settings.recognition.RecognitionBackgroundPreferenceAdapter
import moe.rukamori.archivetune.shared.ui.settings.recognition.RecognitionHistoryPreferenceAdapter
import platform.Foundation.*

/**
 * Music recognition for the iOS root: the AVAudioEngine capture, the common fingerprint, the Shazam matcher over the
 * graph's one client, and the saved history and background setting over the one settings store.
 *
 * One owner serves both the recognition route and its details route. The details page reads the last match from the
 * owner, and that page replaces the recognition page on screen, so an owner closed with the recognition page would
 * lose the match it is asked to show. The owner is created on first use on [uiScope], the composition's UI scope, and
 * [close] releases it and the microphone when the composition leaves.
 *
 * Background recognition needs a service that keeps listening while the app is suspended. This host has none, so the
 * setting is reported unavailable and the owner ignores it.
 */
internal class IosRecognitionMount(
    private val graph: IosSessionGraph,
    private val uiScope: CoroutineScope,
) {
    private val diagnostics = RecognitionDiagnostics { message, cause ->
        graph.report(if (cause == null) message else "$message: ${cause::class.simpleName}")
    }
    private val clock = RecognitionClock { iosNowMillis() }
    private val capture = IosRecognitionCapture()
    private val signatureGenerator = IosRecognitionSignature()
    private val signatures = object : RecognitionSignaturePort {
        override suspend fun generateSignature(samples: ShortArray): String? =
            signatureGenerator.generateSignature(samples)
    }
    private val matcher = ShazamRecognitionMatcher(signatures = signatures, client = graph.http, clock = clock)
    private val history = RecognitionHistoryPreferenceAdapter(graph.settings)
    private val backgroundSetting = RecognitionBackgroundPreferenceAdapter(graph.settings)

    private val dateFormat = NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterShortStyle
    }
    val formatter = RecognitionTimeFormatter { epochMillis ->
        dateFormat.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
    }

    /** Cover art from the recognition reply, loaded like every other remote image on this host. */
    val artwork: RecognitionArtwork = { url, modifier -> IosRemoteArtwork(url, graph.artworkLoader, modifier) }

    private var owner: MusicRecognitionOwner? = null
    private var closed = false

    /** The root's owner, created on first use. Called on the UI thread only. */
    fun owner(): MusicRecognitionOwner {
        check(!closed) { "The recognition mount is closed" }
        return owner ?: MusicRecognitionOwner(
            scope = uiScope,
            capture = capture,
            matcher = matcher,
            permission = capture,
            historyStore = history,
            backgroundSettings = backgroundSetting,
            clock = clock,
            diagnostics = diagnostics,
            backgroundRecognitionAvailable = false,
        ).also { owner = it }
    }

    /** Ends the owner's request and listeners, then releases the microphone. Repeated calls do nothing. */
    fun close() {
        if (closed) return
        closed = true
        owner?.close()
        owner = null
        capture.close()
    }
}

/**
 * The recognition page. Leaving it cancels a listen that is still running, so the microphone is released when the page
 * is no longer shown. The navigation host supplies back, search, the external links and the details navigation; the
 * owner's own details action still settles the shown result first.
 */
@Composable
internal fun iosRecognitionPage(mount: IosRecognitionMount, route: ArchiveRoute): ArchiveGraphPage {
    val owner = mount.owner()
    DisposableEffect(owner) { onDispose { owner.cancel() } }
    val state by owner.state.collectAsState()
    val reducedMotion = rememberIosReduceMotion()
    val ui = remember(state, reducedMotion) { state.toMusicRecognitionUi(mount.formatter, reducedMotion = reducedMotion) }
    val actions = remember(owner) {
        owner.toMusicRecognitionActions(back = {}, search = {}, openUri = {}, openDetails = {})
    }
    return ArchiveGraphPage.MusicRecognition(
        state = ui,
        actions = actions,
        artwork = mount.artwork,
        autoStartRequest = route.arguments["autoStart"]?.toLongOrNull(),
    )
}

/**
 * One saved recognition by its stable key, from the last match or the saved history. A key the owner does not hold
 * shows the shared missing state. The navigation host supplies every action.
 */
@Composable
internal fun iosRecognitionDetailsPage(mount: IosRecognitionMount, route: ArchiveRoute): ArchiveGraphPage {
    val owner = mount.owner()
    val state by owner.state.collectAsState()
    val key = route.arguments["recognitionKey"].orEmpty()
    val track = remember(state, key) { state.detailsFor(key)?.toRecognizedTrackUi() }
    val actions = remember { MusicRecognitionDetailsActions(back = {}, listenAgain = {}, search = {}, openUri = {}) }
    return ArchiveGraphPage.MusicRecognitionDetails(track = track, actions = actions, artwork = mount.artwork)
}
