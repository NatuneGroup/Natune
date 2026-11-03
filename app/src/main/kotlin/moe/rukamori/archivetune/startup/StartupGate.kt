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
 * matching crash inside its window counts as completed once the window has fully elapsed — and a
 * journaled first frame completes it even sooner — so one healthy launch, or any death Android did
 * not call a crash, ends the streak. Records from other builds never count, records at or before the
 * acknowledged attempt were already reviewed and can never re-arm the screen, and a crash with no
 * recorded start inside the window cannot be proven to have happened during startup, so it does not
 * count either. A start that journaled its first frame finished starting up: a later death of that
 * process is a runtime crash and matches nothing.
 */
internal fun consecutiveStartupCrashes(
    starts: List<StartupAttempt>,
    crashes: List<CrashRecord>,
    install: InstallWindow,
    nowMs: Long,
    startupWindowMs: Long = STARTUP_CRASH_WINDOW_MS,
    acknowledgedThroughMs: Long = 0L,
): Int {
    // The same attempt can appear twice — journaled with its first-frame stamp and again from the
    // pending/last marker without one. Merge by identity so the stamped copy wins.
    val orderedStarts = starts
        .groupBy { it.timestampMs to it.pid }
        .map { (_, copies) -> copies.maxBy { it.healthyAtMs } }
        .sortedBy { it.timestampMs }

    // Time and pid alone attribute a death to its launch, whatever the healthy stamp says.
    fun owns(start: StartupAttempt, crash: CrashRecord): Boolean =
        start.timestampMs <= crash.timestampMs &&
            crash.timestampMs - start.timestampMs <= startupWindowMs &&
            (start.pid == 0 || crash.pid == 0 || start.pid == crash.pid)

    val evidence = crashes
        .filter {
            it.kind.isCrashEvidence() && !it.isFromEarlierVersion(install) &&
                it.timestampMs > acknowledgedThroughMs
        }
        // A death of a start that journaled its first frame is a runtime crash, and a death no
        // recorded start can own cannot be proven during startup: neither is startup evidence.
        .filter { crash -> orderedStarts.lastOrNull { owns(it, crash) }?.healthyAtMs == 0L }
        .sortedByDescending { it.timestampMs }
    if (evidence.isEmpty()) return 0

    fun matches(start: StartupAttempt, crash: CrashRecord): Boolean =
        start.healthyAtMs == 0L && owns(start, crash)

    // A start that died — during startup or after its first frame — cannot prove the loop ended.
    fun diedAfterStartup(start: StartupAttempt): Boolean =
        crashes.any {
            it.kind.isCrashEvidence() && !it.isFromEarlierVersion(install) && owns(start, it)
        }

    // The window must be fully over — or the first frame journaled — before a surviving start
    // counts as completed: a start still inside it has simply not crashed yet.
    fun completedStartup(start: StartupAttempt): Boolean =
        !diedAfterStartup(start) &&
            (start.healthyAtMs != 0L || start.timestampMs + startupWindowMs <= nowMs)

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
