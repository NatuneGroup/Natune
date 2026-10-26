package moe.rukamori.archivetune.shared.data.persistence

import moe.rukamori.archivetune.shared.domain.repository.*

/** Adapts the already-tested full-byte store without changing its compatibility envelope. */
class JvmQueueCheckpointAdapter(private val store: JvmLosslessQueueStore) : LosslessQueueCheckpointStore {
    override suspend fun read(): LegacyQueueResume? = store.read()?.let {
        LegacyQueueResume(it.revision, it.document, it.positionOverrideMs)
    }
    override suspend fun write(value: LegacyQueueResume, expectedRevision: Long?): Boolean {
        // The original bytes must contain the captured live position. Do not split an atomic capture
        // into a structural write and a second override write that could race another process.
        require(value.positionOverrideMs == null) { "Capture the live position in the original stream" }
        return store.write(value.revision, value.document.originalBytes(), expectedRevision)
    }
}
