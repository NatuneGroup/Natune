package moe.rukamori.archivetune.shared.domain.automix

/**
 * The default [CompactAutomixAnalyzer] cancellation probe. The Android analyzer runs on a worker
 * thread and checks `Thread.currentThread().isInterrupted`; callers bound to a coroutine pass
 * `ensureActive` themselves. Platforms without a current-thread interrupt flag return a no-op.
 */
internal expect fun defaultAutomixCheckActive(): () -> Unit
