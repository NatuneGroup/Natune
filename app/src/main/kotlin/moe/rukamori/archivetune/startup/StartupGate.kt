package moe.rukamori.archivetune.startup

/**
 * Exit reasons Android and the local capture both call a crash. Every other death — a swipe-away,
 * force-stop, low-memory kill, reboot or update — is only an unclean exit and never crash evidence.
 */
internal fun ProcessExitKind.isCrashEvidence(): Boolean =
    this == ProcessExitKind.CRASH ||
        this == ProcessExitKind.NATIVE_CRASH ||
        this == ProcessExitKind.ANR

/** A death this long after its process start still happened during startup, not at runtime. */
internal const val STARTUP_CRASH_WINDOW_MS = 10_000L

/** One unlucky launch never interrupts: the recovery screen is for a crash loop, not one crash. */
internal const val REQUIRED_CONSECUTIVE_CRASHES = 2

/**
 * Counts the run of newest crash-evidence records of the installed build that each killed a process
 * still inside its startup window, with no completed startup in between. A recorded start with no
 * matching crash inside its window counts as completed once the window has fully elapsed, so one
 * healthy launch — or any death Android did not call a crash — ends the streak. Records from other
 * builds never count, and a crash with no recorded start inside the window cannot be proven to have
 * happened during startup, so it does not count either.
 */
internal fun consecutiveStartupCrashes(
    starts: List<StartupAttempt>,
    crashes: List<CrashRecord>,
    install: InstallWindow,
    nowMs: Long,
    startupWindowMs: Long = STARTUP_CRASH_WINDOW_MS,
): Int {
    val evidence = crashes
        .filter { it.kind.isCrashEvidence() && !it.isFromEarlierVersion(install) }
        .sortedByDescending { it.timestampMs }
    if (evidence.isEmpty()) return 0
    val orderedStarts = starts.sortedBy { it.timestampMs }

    fun matches(start: StartupAttempt, crash: CrashRecord): Boolean =
        start.timestampMs <= crash.timestampMs &&
            crash.timestampMs - start.timestampMs <= startupWindowMs &&
            (start.pid == 0 || crash.pid == 0 || start.pid == crash.pid)

    fun crashedDuringStartup(start: StartupAttempt): Boolean = evidence.any { matches(start, it) }

    // The window must be fully over: a start still inside it has simply not crashed yet.
    fun completedStartup(start: StartupAttempt): Boolean =
        !crashedDuringStartup(start) && start.timestampMs + startupWindowMs <= nowMs

    var streak = 0
    var previousMatchMs = Long.MAX_VALUE
    for ((index, crash) in evidence.withIndex()) {
        val start = orderedStarts.lastOrNull { matches(it, crash) } ?: break
        val interrupted = if (index == 0) {
            // A completed start newer than the newest crashing start means the loop already ended.
            orderedStarts.any { it.timestampMs > start.timestampMs && completedStartup(it) }
        } else {
            // A completed start between this crash and the newer one breaks the run.
            orderedStarts.any {
                it.timestampMs > crash.timestampMs && it.timestampMs < previousMatchMs &&
                    completedStartup(it)
            }
        }
        if (interrupted) break
        streak++
        previousMatchMs = start.timestampMs
    }
    return streak
}
