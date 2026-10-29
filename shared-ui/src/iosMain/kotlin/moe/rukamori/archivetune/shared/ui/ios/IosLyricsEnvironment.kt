@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsFeedTiming
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification

/** The system Reduce Motion setting, kept current while composed, so motion follows the device as it changes. */
@Composable
internal fun rememberIosReduceMotion(): Boolean {
    var reduced by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            UIAccessibilityReduceMotionStatusDidChangeNotification, null, NSOperationQueue.mainQueue,
        ) { _ -> reduced = UIAccessibilityIsReduceMotionEnabled() }
        onDispose { center.removeObserver(observer) }
    }
    return reduced
}

/**
 * The lyric sync offset for the current audio route. The engine reports the rendered position, and the route still
 * holds the sound back by its output latency and I/O buffer (about two seconds on AirPlay), so the lyrics wait for
 * the same span. It is read again whenever the route changes.
 */
@Composable
internal fun rememberIosLyricsSyncOffsetMs(): Long {
    var offset by remember { mutableLongStateOf(iosLyricsSyncOffsetMs()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue,
        ) { _ -> offset = iosLyricsSyncOffsetMs() }
        onDispose { center.removeObserver(observer) }
    }
    return offset
}

internal fun iosLyricsSyncOffsetMs(): Long {
    val session = AVAudioSession.sharedInstance()
    return LyricsFeedTiming.outputLatencyOffsetMs(session.outputLatency, session.IOBufferDuration)
}
