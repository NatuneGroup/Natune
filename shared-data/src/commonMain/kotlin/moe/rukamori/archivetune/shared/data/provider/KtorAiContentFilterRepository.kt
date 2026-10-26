package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.platform.*

data class AiChannelLists(val blocked: Set<String> = emptySet(), val moderate: Set<String> = emptySet())
enum class AiFilterRefreshStatus { UPDATED, DISABLED, FRESH, UNAVAILABLE }
data class AiFilterRefresh(val status: AiFilterRefreshStatus, val lists: AiChannelLists) {
    val updated: Boolean get() = status == AiFilterRefreshStatus.UPDATED
}

/** Existing AiSList sources and four-hour refresh; transport/cache failures keep useful old lists. */
class KtorAiContentFilterRepository(
    private val client: HttpClient,
    private val files: PlatformFiles,
    private val cacheFile: FileHandle,
    private val nowMs: () -> Long,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var lists = AiChannelLists()
    private var updatedAt = 0L

    suspend fun blocks(channel: String, enabled: Boolean, includeModerate: Boolean): Boolean = mutex.withLock {
        if (!enabled) return false
        load()
        val key = normalizeChannelKey(channel) ?: return false
        key in lists.blocked || (includeModerate && key in lists.moderate)
    }

    suspend fun refresh(enabled: Boolean, force: Boolean = false): AiFilterRefresh = mutex.withLock {
        load()
        val now = nowMs()
        if (!force && !enabled) return AiFilterRefresh(AiFilterRefreshStatus.DISABLED, lists)
        if (!force && lists.blocked.isNotEmpty() && now >= updatedAt && now - updatedAt < 4 * 60 * 60_000L)
            return AiFilterRefresh(AiFilterRefreshStatus.FRESH, lists)
        val downloaded = coroutineScope {
            val block = async { fetch("aislist_blocklist.txt") }
            val warn = async { fetch("aislist_warnlist.txt") }
            block.await() to warn.await()
        }
        if (downloaded.first == null && downloaded.second == null) return AiFilterRefresh(AiFilterRefreshStatus.UNAVAILABLE, lists)
        val replacement = AiChannelLists(downloaded.first ?: lists.blocked, downloaded.second ?: lists.moderate)
        val json = buildJsonObject {
            put("updatedAt", now)
            put("blocked", JsonArray(replacement.blocked.sorted().map(::JsonPrimitive)))
            put("moderate", JsonArray(replacement.moderate.sorted().map(::JsonPrimitive)))
        }.toString()
        files.writeAtomic(cacheFile, json.encodeToByteArray())
        lists = replacement; updatedAt = now
        AiFilterRefresh(AiFilterRefreshStatus.UPDATED, lists)
    }

    private suspend fun load() {
        if (loaded) return
        try {
            val root = Json.parseToJsonElement(files.read(cacheFile, 12_000_000).decodeToString()).jsonObject
            lists = AiChannelLists(root.getValue("blocked").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                root.getValue("moderate").jsonArray.map { it.jsonPrimitive.content }.toSet())
            updatedAt = root.getValue("updatedAt").jsonPrimitive.long
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { lists = AiChannelLists(); updatedAt = 0L }
        loaded = true
    }

    private suspend fun fetch(name: String): Set<String>? = try {
        withTimeoutOrNull(65_000) {
            client.prepareGet("https://raw.githubusercontent.com/Override92/AiSList/main/AiSList/$name") {
                header("User-Agent", "ArchiveTune-AiContentFilter")
            }.execute { response ->
                if (response.status.value !in 200..299) null
                else response.readBoundedText(5 * 1024 * 1024).lineSequence().map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('!') }.mapNotNull(::normalizeChannelKey).toSet()
                    .takeIf(Set<String>::isNotEmpty)
            }
        }
    } catch (cancel: CancellationException) { throw cancel }
    catch (failure: Exception) { null }
}

internal fun normalizeChannelKey(value: String): String? = value.trim().removePrefix("@").filter(Char::isLetterOrDigit)
    .lowercase().takeIf(String::isNotBlank)
