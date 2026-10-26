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
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.*
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import platform.Foundation.*
import platform.posix.*
import platform.zlib.*

/**
 * Host composition supplies the ONE M6 store and its real connectivity snapshot; no defaults stand
 * in for either. Construct once, forward foreground events, and close with the host service graph.
 */
fun iosJapaneseLanguagePackManager(
    scope: CoroutineScope,
    workerDispatcher: CoroutineDispatcher,
    snapshots: StateFlow<PreferenceSnapshot?>,
    editSettings: suspend (PreferenceEditor.() -> Unit) -> Unit,
    networkAvailable: suspend () -> Boolean,
): JapaneseLanguagePackManager = JapaneseLanguagePackManager(
    scope, workerDispatcher, snapshots, editSettings, IosJapanesePackIo(), ::primaryIosDeviceRegion, networkAvailable,
)

internal fun primaryIosDeviceRegion(): String? {
    val primary = NSLocale.preferredLanguages.firstOrNull() as? String
    // Never scan secondary languages or use the app's translated UI/content-country preference.
    return primary?.let { NSLocale.localeWithLocaleIdentifier(it).countryCode }
        ?: NSLocale.currentLocale.countryCode
}

/** App-private files need neither a document picker nor a second bookmark/persistence scheme. */
class IosJapanesePackIo : JapanesePackIo {
    private val manager = NSFileManager.defaultManager
    private val directory: NSURL = requireNotNull(
        (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL)
            .URLByAppendingPathComponent("language_packs", true),
    )
    private val installed = requireNotNull(directory.URLByAppendingPathComponent("kuromoji-ipadic-0.9.0.jar"))
    private val partial = requireNotNull(directory.URLByAppendingPathComponent("kuromoji-ipadic-0.9.0.jar.partial"))

    override suspend fun readInstalled(): ByteArray? = read(installed)
    override suspend fun readStaged(): ByteArray? = read(partial)

    override suspend fun prepare() = withContext(Dispatchers.Default) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            check(manager.createDirectoryAtURL(directory, true, null, error.ptr)) { "Dictionary directory unavailable" }
            val attributes = manager.attributesOfFileSystemForPath(requireNotNull(directory.path), error.ptr)
                ?: error("Dictionary storage unavailable")
            val free = (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue
                ?: error("Dictionary storage size unavailable")
            check(free >= KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES * 2L + 4 * 1024 * 1024) {
                "Insufficient dictionary storage"
            }
        }
        discardStaged()
    }

    override suspend fun download(progress: (Long, Long) -> Unit) = downloadJapanesePack(partial, progress)

    override suspend fun promote() = withContext(Dispatchers.Default) {
        ensureActive()
        // Same-directory POSIX rename replaces atomically; no remove-then-move window.
        check(rename(requireNotNull(partial.path), requireNotNull(installed.path)) == 0) {
            "Dictionary promotion failed"
        }
    }

    override suspend fun discardStaged() = delete(partial)
    override suspend fun removeInstalled() = delete(installed)

    private suspend fun delete(url: NSURL) = withContext(Dispatchers.Default) {
        ensureActive()
        if (manager.fileExistsAtPath(requireNotNull(url.path))) {
            check(manager.removeItemAtURL(url, null)) { "Dictionary removal failed" }
        }
    }

    private suspend fun read(url: NSURL): ByteArray? = withContext(Dispatchers.Default) {
        ensureActive()
        if (!manager.fileExistsAtPath(requireNotNull(url.path))) return@withContext null
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            val handle = NSFileHandle.fileHandleForReadingFromURL(url, error.ptr)
                ?: error("Dictionary read unavailable")
            try {
                val result = NSMutableData()
                val ceiling = KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toULong()
                while (true) {
                    ensureActive()
                    val remaining = ceiling - result.length
                    val chunk = handle.readDataUpToLength(minOf(64_000uL, remaining + 1uL), error.ptr)
                        ?: error("Dictionary read failed")
                    check(chunk.length <= remaining) { "Dictionary exceeds size limit" }
                    if (chunk.length == 0uL) break
                    result.appendData(chunk)
                }
                if (result.length == 0uL) byteArrayOf() else result.bytes!!.readBytes(result.length.toInt())
            } finally { handle.closeAndReturnError(null) }
        }
    }

    override suspend fun inflateRaw(compressed: ByteArray, expectedSize: Int): ByteArray = withContext(Dispatchers.Default) {
        require(expectedSize in 1..9_411_048 && compressed.size in 1..KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES)
        // One sentinel byte lets zlib finish an empty end block without accepting excess output.
        val result = ByteArray(expectedSize + 1)
        memScoped {
            val stream = alloc<z_stream>()
            memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
            check(inflateInit2_(stream.ptr, -15, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK)
            try {
                compressed.usePinned { input ->
                    result.usePinned { output ->
                        stream.next_in = input.addressOf(0).reinterpret()
                        stream.avail_in = compressed.size.toUInt()
                        var status: Int
                        do {
                            ensureActive()
                            val offset = stream.total_out.toInt()
                            check(offset < result.size) { "Dictionary inflation exceeds limit" }
                            stream.next_out = output.addressOf(offset).reinterpret()
                            stream.avail_out = minOf(64_000, result.size - offset).toUInt()
                            val previousIn = stream.total_in
                            status = inflate(stream.ptr, Z_NO_FLUSH)
                            check(status == Z_OK || status == Z_STREAM_END) { "Invalid dictionary compression" }
                            check(stream.total_out.toInt() <= expectedSize) { "Dictionary inflation exceeds limit" }
                            check(status == Z_STREAM_END || stream.total_out.toInt() > offset || stream.total_in > previousIn)
                        } while (status != Z_STREAM_END)
                        check(stream.total_out.toInt() == expectedSize && stream.avail_in == 0u)
                    }
                }
            } finally { inflateEnd(stream.ptr) }
        }
        result.copyOf(expectedSize)
    }
}
