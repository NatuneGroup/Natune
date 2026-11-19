
package moe.rukamori.archivetune.playback

suspend fun cleanupCrossfadeLoopFailure(
    handoff: suspend (incomingUnusable: Boolean) -> Unit,
    cancel: () -> Unit,
    clearMixing: () -> Unit,
    clearPauseAtEnd: () -> Unit,
    restoreSkipSilence: () -> Unit,
) {
    // A failed promotion must re-enter the policy as unusable, preserving the committed
    // incoming position. Do not let a second cleanup failure skip the surviving player's setting.
    val handedOff = runCatching { handoff(false) }.isSuccess ||
        runCatching { handoff(true) }.isSuccess
    if (!handedOff) {
        runCatching(cancel)
        runCatching(clearMixing)
        runCatching(clearPauseAtEnd)
    }
    runCatching(restoreSkipSilence)
}
