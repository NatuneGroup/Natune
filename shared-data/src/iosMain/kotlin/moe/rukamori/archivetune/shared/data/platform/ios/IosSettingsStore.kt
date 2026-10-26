/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.platform.*
import moe.rukamori.archivetune.shared.domain.settings.*
import platform.Foundation.*

/**
 * The ordinary M6 SettingsStore on iOS. Its payload lives in device-only Keychain items, never
 * UserDefaults or a plaintext file, so it stays consistent with the vault references it holds across
 * a reinstall. Two fixed bounded slots avoid an unbounded orphan journal; the single head replacement
 * is the publication point. [guard] refuses credential-bearing names before any write: M6's
 * VaultSettingsStore moves those values into scoped vault items and keeps only opaque references here.
 *
 * This is NOT a portable archive or a replacement account vault: providers still use the separate
 * scoped SecureCredentialStore. Export/restore consumes PreferenceSnapshot through M7's policy.
 * Callers must not write this store directly once VaultSettingsStore owns it.
 */
class IosSettingsStore private constructor(
    private val vault: IosKeychainCredentialStore,
    private val guard: (PreferenceSnapshot) -> Unit,
    private var head: Head?,
    initial: PreferenceSnapshot,
) : SettingsStore {
    private val gate = Mutex()
    private var closed = false
    private val values = MutableStateFlow<PreferenceSnapshot?>(initial)
    override val snapshots = values.asStateFlow()
    private val pending = MutableStateFlow(false)
    /** Commit is durable; an obsolete encrypted slot still needs best-effort cleanup. */
    val cleanupPending = pending.asStateFlow()

    override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange = gate.withLock {
        check(!closed)
        val before = requireNotNull(values.value)
        commit(before, before.toEditor().apply(change).snapshot())
    }

    private suspend fun commit(before: PreferenceSnapshot, after: PreferenceSnapshot): SettingsChange {
        // Fail closed before any Keychain write: credential-bearing names belong only to the vault.
        guard(after)
        val change = SettingsChange(before, after)
        if (change.changedKeys.isEmpty()) return change
        currentCoroutineContext().ensureActive()
        val bytes = withContext(Dispatchers.Default) { IosPreferencePayload.encode(after) }
        try {
            withContext(Dispatchers.Default + NonCancellable) {
                val slot = 1 - (head?.slot ?: 1)
                clearSlot(slot)
                val count = (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
                val next = Head(slot, count, bytes.size, ContentDigest.sha256(bytes))
                // Record the upper bound BEFORE writing chunks, including in a crash-abandoned slot.
                vault.write(key("slot/$slot/count"), count.toString())
                var published = false
                try {
                    repeat(count) { index ->
                        val from = index * CHUNK_BYTES
                        val chunk = bytes.copyOfRange(from, minOf(from + CHUNK_BYTES, bytes.size))
                        try { vault.write(key("slot/$slot/$index"), chunk.base64()) }
                        finally { chunk.fill(0) }
                    }
                    vault.write(key("head"), next.encode())
                    head = next
                    values.value = after
                    published = true
                } finally {
                    if (!published) clearSlot(slot)
                }
                try { clearSlot(1 - slot); pending.value = false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { pending.value = true }
            }
        } finally { bytes.fill(0) }
        return change
    }

    private suspend fun clearSlot(slot: Int) {
        // A corrupt count must not block every later write; removing absent items is a no-op.
        val count = vault.read(key("slot/$slot/count"))?.toIntOrNull()?.coerceIn(0, MAX_CHUNKS) ?: 0
        repeat(count) { vault.remove(key("slot/$slot/$it")) }
        vault.remove(key("slot/$slot/count"))
    }

    suspend fun close() = withContext(NonCancellable) {
        gate.withLock {
            if (!closed) {
                closed = true
                values.value = null
                owner.withLock { occupied = false }
            }
        }
    }

    private data class Head(val slot: Int, val count: Int, val size: Int, val digest: String) {
        fun encode(): String = buildJsonObject {
            put("version", 1); put("slot", slot); put("count", count); put("size", size); put("sha256", digest)
        }.toString()
    }

    companion object {
        private const val CHUNK_BYTES = 32_000
        private const val MAX_CHUNKS = (IosPreferencePayload.MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES
        private val owner = Mutex()
        private var occupied = false
        private fun key(name: String) = CredentialKey("ios-preferences", CredentialScope.PERSONAL, name)

        /**
         * Call on a host worker, not synchronously from Swift/Compose startup. Errors never reset data.
         * [guard] is M6's credential-free check; it runs before every write, never on the migration read.
         */
        suspend fun open(guard: (PreferenceSnapshot) -> Unit): IosSettingsStore {
            owner.withLock { check(!occupied) { "iOS settings already have an owner" }; occupied = true }
            var opened: IosSettingsStore? = null
            try {
                return withContext(Dispatchers.Default) {
                    val vault = IosKeychainCredentialStore("moe.rukamori.archivetune.preferences")
                    val raw = vault.read(key("head"))
                    val head = raw?.let {
                        val json = Json.parseToJsonElement(it).jsonObject
                        require(json.keys == setOf("version", "slot", "count", "size", "sha256"))
                        require(json.getValue("version").jsonPrimitive.int == 1)
                        Head(json.getValue("slot").jsonPrimitive.int, json.getValue("count").jsonPrimitive.int,
                            json.getValue("size").jsonPrimitive.int, json.getValue("sha256").jsonPrimitive.content).also { h ->
                            require(h.slot in 0..1 && h.size in 1..IosPreferencePayload.MAX_BYTES)
                            require(h.count == (h.size + CHUNK_BYTES - 1) / CHUNK_BYTES)
                            require(h.digest.length == 64 && h.digest.all { it in "0123456789abcdef" })
                        }
                    }
                    val initial = if (head == null) PreferenceSnapshot.EMPTY else {
                        val bytes = ByteArray(head.size)
                        try {
                            repeat(head.count) { index ->
                                currentCoroutineContext().ensureActive()
                                val chunk = requireNotNull(vault.read(key("slot/${head.slot}/$index"))).unbase64()
                                try {
                                    val from = index * CHUNK_BYTES
                                    require(chunk.size == minOf(CHUNK_BYTES, bytes.size - from))
                                    chunk.copyInto(bytes, from)
                                } finally { chunk.fill(0) }
                            }
                            require(ContentDigest.sha256(bytes) == head.digest) { "Invalid iOS settings payload" }
                            IosPreferencePayload.decode(bytes)
                        } finally { bytes.fill(0) }
                    }
                    IosSettingsStore(vault, guard, head, initial).also { store ->
                        opened = store
                        try { store.clearSlot(1 - (head?.slot ?: 1)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { store.pending.value = true }
                    }
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    opened?.close() ?: owner.withLock { occupied = false }
                }
                throw failure
            }
        }

        private fun ByteArray.base64(): String = usePinned {
            NSData.create(bytes = it.addressOf(0), length = size.toULong()).base64EncodedStringWithOptions(0u)
        }
        private fun String.unbase64(): ByteArray {
            require(length <= (CHUNK_BYTES + 2) / 3 * 4)
            val data = requireNotNull(NSData.create(base64EncodedString = this, options = 0u))
            require(data.length in 1uL..CHUNK_BYTES.toULong())
            return data.bytes!!.readBytes(data.length.toInt())
        }
    }
}
