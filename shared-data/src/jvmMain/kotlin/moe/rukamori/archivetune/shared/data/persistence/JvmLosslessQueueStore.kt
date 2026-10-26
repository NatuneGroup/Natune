/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.persistence

import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.persistence.LegacyQueueDocument
import moe.rukamori.archivetune.shared.domain.platform.*

data class StoredLegacyQueue(val revision: Long, val document: LegacyQueueDocument, val positionOverrideMs: Long?) {
    val positionMs: Long get() = positionOverrideMs ?: LegacyQueueAccess(document).number(LegacyQueueAccess(document).queue, "position")
}

/** Separate compatibility envelope. Revisions are NEW owner revisions, not invented legacy history. */
class JvmLosslessQueueStore(root: Path) {
    private val root = Files.createDirectories(root.toAbsolutePath().normalize()).toRealPath()
    private val files = JvmPlatformFiles(this.root)
    // FileChannel locks overlap (throw) rather than wait between instances in the SAME JVM.
    // Fixed stripes bound memory while serializing aliases of one canonical store before its OS lock.
    private val mutex = PROCESS_LOCKS[(this.root.hashCode() and Int.MAX_VALUE) % PROCESS_LOCKS.size]
    private val handle = FileHandle("legacy-queue.json")
    private val reader = LegacyJavaQueueReader()

    suspend fun read(): StoredLegacyQueue? = locked { readLocked() }
    suspend fun write(revision: Long, javaBytes: ByteArray, expectedRevision: Long?): Boolean {
        require(revision >= 0)
        val document = reader.read(javaBytes)
        return locked {
            val old = readLocked()
            if (old?.revision != expectedRevision || (old != null && revision <= old.revision)) return@locked false
            writeLocked(StoredLegacyQueue(revision, document, null)); true
        }
    }
    /** Revision plus timeline index disambiguates duplicates; selecting another occurrence requires a new revision. */
    suspend fun updatePosition(expectedRevision: Long, occurrenceIndex: Int, positionMs: Long): Boolean {
        require(positionMs >= 0)
        return locked {
            val old = readLocked() ?: return@locked false
            val access = LegacyQueueAccess(old.document)
            if (old.revision != expectedRevision || occurrenceIndex !in access.items().indices ||
                occurrenceIndex.toLong() != access.number(access.queue, "mediaItemIndex"))
                return@locked false
            writeLocked(old.copy(positionOverrideMs = positionMs)); true
        }
    }
    private suspend fun readLocked(): StoredLegacyQueue? {
        val raw = try { files.read(handle, 48L * 1024 * 1024) } catch (missing: NoSuchFileException) { return null }
        val json = Json.parseToJsonElement(raw.decodeToString()).jsonObject
        require(json.keys == setOf("format", "version", "revision", "positionOverrideMs", "sha256", "payload"))
        require(json.getValue("format").jsonPrimitive.content == "archivetune-legacy-queue" &&
            json.getValue("version").jsonPrimitive.int == 1)
        val revision = json.getValue("revision").jsonPrimitive.long.also { require(it >= 0) }
        val position = json.getValue("positionOverrideMs").takeUnless { it is JsonNull }?.jsonPrimitive?.long
        require(position == null || position >= 0)
        val bytes = Base64.getDecoder().decode(json.getValue("payload").jsonPrimitive.content)
        require(ContentDigest.sha256(bytes) == json.getValue("sha256").jsonPrimitive.content) { "Corrupt legacy envelope" }
        return StoredLegacyQueue(revision, reader.read(bytes), position)
    }
    private suspend fun writeLocked(value: StoredLegacyQueue) {
        val bytes = value.document.originalBytes()
        val json = buildJsonObject {
            put("format", "archivetune-legacy-queue"); put("version", 1); put("revision", value.revision)
            put("positionOverrideMs", value.positionOverrideMs?.let(::JsonPrimitive) ?: JsonNull)
            put("sha256", ContentDigest.sha256(bytes)); put("payload", Base64.getEncoder().encodeToString(bytes))
        }
        files.writeAtomic(handle, json.toString().encodeToByteArray())
    }
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val lock = root.resolve("legacy-queue.lock")
            require(!Files.isSymbolicLink(lock))
            FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { block() }
            }
        }
    }
    private companion object { val PROCESS_LOCKS = Array(64) { Mutex() } }
}
