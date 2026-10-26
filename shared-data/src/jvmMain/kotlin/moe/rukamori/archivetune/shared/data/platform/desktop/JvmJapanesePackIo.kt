package moe.rukamori.archivetune.shared.data.platform.desktop

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.zip.Inflater
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseLanguagePackManager
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapanesePackIo
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.KuromojiJapaneseTokenizerFactory
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/**
 * Host composition supplies the ONE M6 store, its HTTP client and its real connectivity snapshot; no
 * defaults stand in for any of them. Construct once, forward foreground events, and close with the
 * host service graph. The pack lives inside the host's own instance directory, never a second
 * storage scheme.
 */
fun jvmJapaneseLanguagePackManager(
    scope: CoroutineScope,
    workerDispatcher: CoroutineDispatcher,
    snapshots: StateFlow<PreferenceSnapshot?>,
    editSettings: suspend (PreferenceEditor.() -> Unit) -> Unit,
    networkAvailable: suspend () -> Boolean,
    instanceDirectory: Path,
    http: HttpClient,
): JapaneseLanguagePackManager = JapaneseLanguagePackManager(
    scope, workerDispatcher, snapshots, editSettings, JvmJapanesePackIo(instanceDirectory, http),
    ::primaryJvmDeviceRegion, networkAvailable,
)

/** The primary system locale's region only; secondary UI languages never enter the JP consideration. */
internal fun primaryJvmDeviceRegion(): String? =
    Locale.getDefault().country.takeIf { it.isNotBlank() }

/** App-private files need neither a document picker nor a second bookmark/persistence scheme. */
class JvmJapanesePackIo(instanceDirectory: Path, private val http: HttpClient) : JapanesePackIo {
    private val directory: Path = instanceDirectory.resolve("language_packs")
    private val installed: Path = directory.resolve("kuromoji-ipadic-0.9.0.jar")
    private val partial: Path = directory.resolve("kuromoji-ipadic-0.9.0.jar.partial")

    override suspend fun readInstalled(): ByteArray? = read(installed)
    override suspend fun readStaged(): ByteArray? = read(partial)

    override suspend fun prepare() {
        withContext(Dispatchers.IO) {
            Files.createDirectories(directory)
            val free = Files.getFileStore(directory).usableSpace
            check(free >= KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES * 2L + 4 * 1024 * 1024) {
                "Insufficient dictionary storage"
            }
        }
        discardStaged()
    }

    override suspend fun download(progress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        withTimeout(180_000) {
            http.prepareGet(KuromojiJapaneseTokenizerFactory.ARCHIVE_URL).execute { response ->
                check(response.status.value == 200) { "Dictionary response rejected" }
                val total = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                check(total < 0 || total == KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toLong()) {
                    "Dictionary response rejected"
                }
                val channel = response.bodyAsChannel()
                // The byte ceiling is checked BEFORE each write, and the channel honours cancellation.
                FileChannel.open(
                    partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { output ->
                    val chunk = ByteArray(64_000)
                    var downloaded = 0L
                    while (!channel.isClosedForRead) {
                        ensureActive()
                        val remaining = KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toLong() - downloaded
                        val read = channel.readAvailable(chunk, 0, minOf(chunk.size.toLong(), remaining + 1).toInt())
                        if (read == -1) break
                        check(read.toLong() <= remaining) { "Dictionary exceeds size limit" }
                        output.write(ByteBuffer.wrap(chunk, 0, read))
                        downloaded += read
                        progress(downloaded, total)
                    }
                    output.force(true)
                    check(downloaded == KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toLong()) {
                        "Incomplete dictionary transfer"
                    }
                }
            }
        }
    }

    override suspend fun promote() {
        withContext(Dispatchers.IO) {
            ensureActive()
            // Same-directory rename replaces atomically; no remove-then-move window.
            try {
                Files.move(partial, installed, StandardCopyOption.ATOMIC_MOVE)
            } catch (atomicUnsupported: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(partial, installed, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    override suspend fun discardStaged() {
        delete(partial)
    }

    override suspend fun removeInstalled() {
        delete(installed)
    }

    private suspend fun delete(path: Path) = withContext(Dispatchers.IO) {
        ensureActive()
        Files.deleteIfExists(path)
    }

    private suspend fun read(path: Path): ByteArray? = withContext(Dispatchers.IO) {
        ensureActive()
        if (!Files.isRegularFile(path)) return@withContext null
        val ceiling = KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toLong()
        val result = ByteArray(0)
        FileChannel.open(path, StandardOpenOption.READ).use { input ->
            val chunks = mutableListOf<ByteArray>()
            var size = 0L
            val buffer = ByteBuffer.allocate(64_000)
            while (true) {
                ensureActive()
                buffer.clear()
                val read = input.read(buffer)
                if (read == -1) break
                size += read
                check(size <= ceiling) { "Dictionary exceeds size limit" }
                chunks.add(buffer.array().copyOf(read))
            }
            if (size == 0L) return@withContext result
            val merged = ByteArray(size.toInt())
            var offset = 0
            for (piece in chunks) {
                System.arraycopy(piece, 0, merged, offset, piece.size)
                offset += piece.size
            }
            merged
        }
    }

    override suspend fun inflateRaw(compressed: ByteArray, expectedSize: Int): ByteArray = withContext(Dispatchers.IO) {
        require(expectedSize in 1..9_411_048 && compressed.size in 1..KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES)
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val result = ByteArray(expectedSize)
            var offset = 0
            while (offset < expectedSize && !inflater.finished()) {
                ensureActive()
                val produced = inflater.inflate(result, offset, expectedSize - offset)
                check(produced > 0 || !inflater.needsInput() && !inflater.finished() && !inflater.needsDictionary()) {
                    "Invalid dictionary compression"
                }
                offset += produced
            }
            check(offset == expectedSize && inflater.finished() && inflater.remaining == 0) {
                "Dictionary inflation incomplete"
            }
            result
        } finally {
            inflater.end()
        }
    }
}
