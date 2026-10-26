package moe.rukamori.archivetune.shared.domain.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * Validated archives waiting for the user to choose sections. Manual files and Drive downloads both register
 * here, so the UI sees one handle type and every restore goes through the same validated, transactional path.
 */
interface PreparedRestoreRegistry {
    /**
     * Validates [archive] before anything is applied and registers it. A bad archive returns
     * [PreparedArchive.Rejected] instead of throwing, matching the baseline restore screen.
     */
    suspend fun prepare(archive: ByteArray): PreparedArchive

    /**
     * Applies the [selected] sections of a prepared archive through the gateway's transactional restore.
     * The handle stays registered when the restore fails, so the user can retry or discard it.
     */
    suspend fun restore(handle: String, selected: Set<BackupDataCategory>): BackupRestoreOutcome

    /** Forgets a prepared archive without applying it. Unknown handles are ignored. */
    suspend fun discard(handle: String)
}

/**
 * Keeps prepared archives in memory for one registry instance. Handles are opaque strings that include a
 * per-instance key, so a handle from another registry is unknown here. At most [MAX_PREPARED_ARCHIVES]
 * entries are kept, which bounds memory held by large archives.
 */
class InMemoryPreparedRestoreRegistry(
    private val gateway: BackupArchiveGateway,
) : PreparedRestoreRegistry {
    private class Entry(
        val archive: ByteArray,
        val categories: Set<BackupDataCategory>,
    ) {
        /** True while a restore for this entry runs, so one archive cannot be applied twice at once. */
        var busy: Boolean = false
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private val ownerKey = Random.nextLong().toULong().toString(16)
    private var sequence = 0L

    override suspend fun prepare(archive: ByteArray): PreparedArchive {
        val inspection = try {
            gateway.inspect(archive)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: BackupArchiveException) {
            return PreparedArchive.Rejected(rejected.problem)
        } catch (unexpected: Exception) {
            // Matches the baseline restore screen: an unreadable archive reports as corrupted.
            return PreparedArchive.Rejected(BackupArchiveProblem.CORRUPTED)
        }
        if (inspection.categories.isEmpty()) {
            return PreparedArchive.Rejected(BackupArchiveProblem.NO_RESTORABLE_DATA)
        }
        return mutex.withLock {
            while (entries.size >= MAX_PREPARED_ARCHIVES) {
                entries.remove(entries.keys.first())
            }
            sequence += 1
            val handle = "$ownerKey-${sequence.toString(16)}-${Random.nextLong().toULong().toString(16)}"
            entries[handle] = Entry(archive, inspection.categories)
            PreparedArchive.Ready(handle, inspection.categories)
        }
    }

    override suspend fun restore(handle: String, selected: Set<BackupDataCategory>): BackupRestoreOutcome {
        val (entry, chosen) = mutex.withLock {
            val found = entries[handle] ?: throw BackupArchiveException(BackupArchiveProblem.UNAVAILABLE)
            if (found.busy) throw BackupArchiveException(BackupArchiveProblem.UNAVAILABLE)
            val sections = selected intersect found.categories
            if (sections.isEmpty()) throw BackupArchiveException(BackupArchiveProblem.NO_RESTORABLE_DATA)
            found.busy = true
            found to sections
        }
        val outcome = try {
            gateway.restore(entry.archive, chosen)
        } catch (failure: Throwable) {
            // The gateway rolled back, so the handle stays available for a retry or a discard.
            withContext(NonCancellable) {
                mutex.withLock { entry.busy = false }
            }
            throw failure
        }
        withContext(NonCancellable) {
            mutex.withLock { entries.remove(handle) }
        }
        return outcome
    }

    override suspend fun discard(handle: String) {
        withContext(NonCancellable) {
            mutex.withLock { entries.remove(handle) }
        }
    }

    private companion object {
        const val MAX_PREPARED_ARCHIVES = 2
    }
}
