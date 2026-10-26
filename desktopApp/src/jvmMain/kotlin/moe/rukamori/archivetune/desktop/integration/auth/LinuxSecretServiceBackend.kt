/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration.auth

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.desktop.integration.DesktopStrings

/**
 * libsecret owns Secret Service session negotiation/encryption and OS keyring persistence.
 * No password-tool subprocess, argv/environment secret, plaintext file or fallback backend.
 * Locked search matches fail instead of pretending to be missing or successfully removed.
 */
internal class LinuxSecretServiceBackend : CredentialBackend {
    private interface Secret : Library {
        fun secret_schema_newv(name: String, flags: Int, attributes: Pointer): Pointer?
        fun secret_schema_unref(schema: Pointer)
        fun secret_service_get_sync(flags: Int, cancellable: Pointer, error: PointerByReference): Pointer?
        fun secret_service_search_sync(service: Pointer, schema: Pointer, attributes: Pointer, flags: Int,
            cancellable: Pointer, error: PointerByReference): Pointer?
        fun secret_service_store_sync(service: Pointer, schema: Pointer, attributes: Pointer, collection: String?,
            label: String, value: Pointer, cancellable: Pointer, error: PointerByReference): Int
        fun secret_item_get_locked(item: Pointer): Int
        fun secret_item_load_secret_sync(item: Pointer, cancellable: Pointer, error: PointerByReference): Int
        fun secret_item_get_secret(item: Pointer): Pointer?
        fun secret_item_delete_sync(item: Pointer, cancellable: Pointer, error: PointerByReference): Int
        fun secret_value_new(value: Pointer, size: NativeLong, contentType: String): Pointer?
        fun secret_value_get(value: Pointer, size: NativeLongByReference): Pointer?
        fun secret_value_unref(value: Pointer)
    }
    private interface Glib : Library {
        fun g_hash_table_new(hash: Pointer, equal: Pointer): Pointer
        fun g_hash_table_insert(table: Pointer, key: Pointer, value: Pointer?): Int
        fun g_hash_table_unref(table: Pointer)
        fun g_list_free_full(list: Pointer, destroy: Pointer)
        fun g_error_free(error: Pointer)
    }
    private interface Gio : Library {
        fun g_cancellable_new(): Pointer
        fun g_cancellable_cancel(cancellable: Pointer)
    }
    private interface Gobject : Library { fun g_object_unref(pointer: Pointer) }
    private val secret by lazy { Native.load("libsecret-1.so.0", Secret::class.java) }
    private val glib by lazy { Native.load("libglib-2.0.so.0", Glib::class.java) }
    private val gio by lazy { Native.load("libgio-2.0.so.0", Gio::class.java) }
    private val objects by lazy { Native.load("libgobject-2.0.so.0", Gobject::class.java) }

    override suspend fun read(account: String): ByteArray? = operation(account) { service, schema, attributes, cancel ->
        matches(service, schema, attributes, cancel) { items ->
            check(items.size <= 1)
            val item = items.singleOrNull() ?: return@matches null
            nativeResult { secret.secret_item_load_secret_sync(item, cancel, it) }.also { check(it != 0) }
            val value = checkNotNull(secret.secret_item_get_secret(item))
            try {
                val size = NativeLongByReference()
                val pointer = secret.secret_value_get(value, size)
                val count = size.value.toLong()
                check(count in 0..MAX_CREDENTIAL_BYTES.toLong())
                if (count == 0L) byteArrayOf() else checkNotNull(pointer).getByteArray(0, count.toInt())
            } finally { secret.secret_value_unref(value) }
        }
    }
    override suspend fun write(account: String, value: ByteArray) {
        // The caller may resume cancellation and wipe its buffer before native cancellation settles.
        val owned = value.copyOf()
        operation(account, cleanup = { owned.fill(0) }) { service, schema, attributes, cancel ->
            matches(service, schema, attributes, cancel) { check(it.size <= 1) }
            Memory(maxOf(1, owned.size).toLong()).use { memory ->
                try {
                    memory.write(0, owned, 0, owned.size)
                    val native = checkNotNull(secret.secret_value_new(memory, NativeLong(owned.size.toLong()), "application/octet-stream"))
                    try {
                        check(nativeResult { secret.secret_service_store_sync(service, schema, attributes, null,
                            DesktopStrings["credential_label"], native, cancel, it) } != 0)
                    } finally { secret.secret_value_unref(native) }
                } finally { memory.clear() }
            }
            null
        }
    }
    override suspend fun remove(account: String) {
        operation(account) { service, schema, attributes, cancel ->
            matches(service, schema, attributes, cancel) { items ->
                for (item in items) check(nativeResult { secret.secret_item_delete_sync(item, cancel, it) } != 0)
            }
            // Do not turn libsecret's "only unlocked items removed" behavior into silent success.
            matches(service, schema, attributes, cancel) { check(it.isEmpty()) }
            null
        }
    }
    private fun <T> nativeResult(call: (PointerByReference) -> T): T {
        val error = PointerByReference()
        val result = call(error)
        error.value?.let { glib.g_error_free(it); throw DesktopCredentialStoreUnavailable() }
        return result
    }
    private fun <T> matches(service: Pointer, schema: Pointer, attributes: Pointer, cancel: Pointer,
        use: (List<Pointer>) -> T): T {
        val list = nativeResult { secret.secret_service_search_sync(service, schema, attributes, 2, cancel, it) }
        val items = mutableListOf<Pointer>()
        try {
            var node = list
            while (node != null) {
                check(items.size < 32)
                items += node.getPointer(0)
                node = node.getPointer(Native.POINTER_SIZE.toLong())
            }
            check(items.none { secret.secret_item_get_locked(it) != 0 })
            return use(items)
        } finally {
            // GList data contains owned GObject references, not secret buffers.
            list?.let { glib.g_list_free_full(it, NativeLibrary.getInstance("libgobject-2.0.so.0").getFunction("g_object_unref")) }
        }
    }
    private suspend fun operation(account: String, cleanup: () -> Unit = {},
        call: (Pointer, Pointer, Pointer, Pointer) -> ByteArray?): ByteArray? {
        var scheduled = false
        try { return withTimeout(15_000) {
            require(account.matches(Regex("[0-9a-f]{64}")))
            suspendCancellableCoroutine { continuation ->
                scheduled = true
                Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
                var cancel: Pointer? = null
                val lifetime = Any()
                try {
                    // Load before starting any native operation so absence is a capability error.
                    secret
                    cancel = gio.g_cancellable_new()
                    continuation.invokeOnCancellation { synchronized(lifetime) { cancel?.let(gio::g_cancellable_cancel) } }
                    val result = withResources(account, checkNotNull(cancel), call)
                    continuation.resume(result, onCancellation = { _, bytes, _ -> bytes?.fill(0) })
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                } finally {
                    try { synchronized(lifetime) { cancel?.let(objects::g_object_unref); cancel = null } }
                    finally { cleanup() }
                }
                })
            }
        } } finally { if (!scheduled) cleanup() }
    }
    private fun withResources(account: String, cancel: Pointer,
        call: (Pointer, Pointer, Pointer, Pointer) -> ByteArray?): ByteArray? {
        val library = NativeLibrary.getInstance("libglib-2.0.so.0")
        val schemaTypes = glib.g_hash_table_new(library.getFunction("g_str_hash"), library.getFunction("g_str_equal"))
        val attributes = glib.g_hash_table_new(library.getFunction("g_str_hash"), library.getFunction("g_str_equal"))
        Memory(8).use { key -> Memory(65).use { identity ->
            key.setString(0, "account", "UTF-8")
            identity.setString(0, account, "US-ASCII")
            var schema: Pointer? = null
            var service: Pointer? = null
            try {
                glib.g_hash_table_insert(schemaTypes, key, null) // SECRET_SCHEMA_ATTRIBUTE_STRING = 0
                glib.g_hash_table_insert(attributes, key, identity)
                schema = checkNotNull(secret.secret_schema_newv("moe.rukamori.archivetune.credentials.v1", 0, schemaTypes))
                service = checkNotNull(nativeResult { secret.secret_service_get_sync(2, cancel, it) })
                return call(service, schema, attributes, cancel)
            } finally {
                service?.let(objects::g_object_unref)
                schema?.let(secret::secret_schema_unref)
                glib.g_hash_table_unref(attributes)
                glib.g_hash_table_unref(schemaTypes)
            }
        } }
    }
}
