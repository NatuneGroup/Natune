@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.modules

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.data.backup.PortableZip
import moe.rukamori.archivetune.shared.data.backup.readPortableFileBytes
import moe.rukamori.archivetune.shared.data.backup.writePortableFileBytes
import moe.rukamori.archivetune.shared.domain.platform.ContentDigest
import moe.rukamori.archivetune.shared.domain.platform.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ModulesEnabledKey
import moe.rukamori.archivetune.shared.domain.source.*
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.posix.rename
import kotlin.io.encoding.Base64

class IosModuleInfo(val id: String, val name: String, val metadata: JsonObject)

/** Readable API-v1 packages use the retained module-host.js, with bounded private requests and Keychain configuration. */
class IosModulesRepository(
    client: HttpClient,
    private val root: String,
    private val credentials: SecureCredentialStore,
    private val preferences: () -> PreferenceSnapshot?,
) : AudioProviderRepository {
    override val source = AudioSourceType.MODULES
    private val http = client.config { followRedirects = false; expectSuccess = false }
    private val lock = Mutex()
    private val manager = NSFileManager.defaultManager
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private fun key(id: String) = CredentialKey("modules", CredentialScope.PERSONAL, id)
    private fun path(id: String): String { require(id.matches(Regex("^[A-Za-z0-9._-]{1,128}$")) && id != ".." && id != "."); return "$root/$id.json" }
    fun installed(): List<IosModuleInfo> = manager.contentsOfDirectoryAtPath(root, null).orEmpty()
        .filterIsInstance<String>().filter { it.endsWith(".json") }.take(32).mapNotNull { name ->
            try { val data = Json.parseToJsonElement(readPortableFileBytes("$root/$name", 5L * 1024 * 1024).decodeToString()).jsonObject
                val meta = data.getValue("metadata").jsonObject
                IosModuleInfo(meta.getValue("id").jsonPrimitive.content, meta.getValue("name").jsonPrimitive.content, meta)
            } catch (_: Exception) { null }
        }
    suspend fun install(bytes: ByteArray): IosModuleInfo = lock.withLock {
        require(bytes.size in 1..4 * 1024 * 1024)
        val entries = PortableZip.read(bytes, 3, { name -> when (name) {
            "manifest.json" -> 128 * 1024L; "provider.js" -> 4 * 1024 * 1024L; "icon.png" -> 256 * 1024L
            else -> error("Unsupported module entry")
        } }, 5 * 1024 * 1024L)
        require(entries.distinctBy { it.name }.size == entries.size)
        val files = entries.associate { it.name to it.data }
        val manifest = Json.parseToJsonElement(requireNotNull(files["manifest.json"]).decodeToString()).jsonObject
        require(manifest["format"]?.jsonPrimitive?.content == "archivetune.module" &&
            manifest["formatVersion"]?.jsonPrimitive?.int == 1 && manifest["apiVersion"]?.jsonPrimitive?.int == 1 &&
            manifest["entry"]?.jsonPrimitive?.content == "provider.js")
        require(manifest["runtime"]?.jsonPrimitive?.content in setOf(null, "archivetune")) { "Extension runtime unavailable" }
        val script = requireNotNull(files["provider.js"])
        require(ContentDigest.sha256(script).equals(manifest["sha256"]?.jsonPrimitive?.content, true)) { "Module checksum mismatch" }
        val code = script.decodeToString(throwOnInvalidSequence = true)
        require(!code.trimStart().startsWith("8SM1.")) { "Encrypted module envelope unsupported" }
        val metadata = manifest.getValue("module").jsonObject
        val id = metadata.getValue("id").jsonPrimitive.content
        path(id)
        require(metadata["signedSession"] == null && metadata["permissions"] == null) { "Signed extension unavailable" }
        val features = (metadata["requiredRuntimeFeatures"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        require(features.all { it == "natuneExtensions@1" }) { "Unsupported module feature" }
        val version = metadata["minAppVersion"]?.jsonPrimitive?.content.orEmpty()
        if (version.isNotBlank()) {
            val parts = version.split('.').map { it.toIntOrNull() ?: error("Invalid module app version") }
            val floor = listOf(15, 1, 0)
            require(parts.size <= 3)
            val order = (0..2).map { (parts.getOrElse(it) { 0 }).compareTo(floor[it]) }.firstOrNull { it != 0 } ?: 0
            require(order <= 0) { "Module requires a newer API host" }
        }
        manager.createDirectoryAtPath(root, true, null, null)
        require(installed().size < 32 || manager.fileExistsAtPath(path(id)))
        val target = path(id)
        val temporary = "$target.tmp"
        try {
            writePortableFileBytes(temporary, buildJsonObject { put("metadata", metadata); put("code", code) }.toString().encodeToByteArray())
            check(rename(temporary, target) == 0) { "Cannot commit module package" }
        } finally { manager.removeItemAtPath(temporary, null) }
        IosModuleInfo(id, metadata.getValue("name").jsonPrimitive.content, metadata)
    }
    suspend fun configure(id: String, json: String) { require(json.length <= 64 * 1024); Json.parseToJsonElement(json).jsonObject; credentials.write(key(id), json) }
    suspend fun configuration(id: String): String = credentials.read(key(id)) ?: "{}"
    suspend fun remove(id: String) = lock.withLock { manager.removeItemAtPath(path(id), null); credentials.remove(key(id)) }

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? = lock.withLock {
        if (preferences()?.get(ModulesEnabledKey) != true) return@withLock null
        for (module in installed()) {
            val custom = Json.parseToJsonElement(configuration(module.id)).jsonObject
            val defaults = (module.metadata["settings"] as? JsonObject).orEmpty().mapValues {
                (it.value as? JsonObject)?.let { row -> row["defaultValue"] ?: row["value"] } ?: JsonNull
            }
            val settings = JsonObject(defaults + custom)
            val types = module.metadata["type"]
            if ((types as? JsonPrimitive)?.content in setOf("lyrics_provider", "metadata_provider")) continue
            if (settings["enabled"]?.jsonPrimitive?.booleanOrNull == false) continue
            val context = buildJsonObject { put("settings", settings); put("track", buildJsonObject {
                put("id", request.mediaId); put("title", request.target.title)
                put("artist", request.target.artists.joinToString(", "))
            }) }
            val found = call(module, "searchTracks", buildJsonArray {
                add(JsonPrimitive((listOf(request.target.title) + request.target.artists).joinToString(" ")))
                add(JsonPrimitive(10)); add(context)
            })
            val rows = (found as? JsonArray) ?: (found as? JsonObject)?.get("tracks") as? JsonArray ?: continue
            val tracks = rows.mapNotNull { value -> (value as? JsonObject)?.let { row ->
                val id = row["id"]?.jsonPrimitive?.content ?: return@let null
                val title = row["title"]?.jsonPrimitive?.content ?: row["name"]?.jsonPrimitive?.content ?: return@let null
                val artists = (row["artists"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }
                    ?: listOfNotNull(row["artist"]?.jsonPrimitive?.contentOrNull)
                TrackMatching.Candidate(id, title, artists, null, row["durationMs"]?.jsonPrimitive?.longOrNull)
            } }
            val track = TrackMatching.best(request.target, tracks) ?: continue
            val result = call(module, "getTrackStreamUrl", buildJsonArray {
                add(JsonPrimitive(track.id)); add(settings["quality"] ?: JsonNull); add(context)
            }) as? JsonObject ?: continue
            val url = result["streamUrl"]?.jsonPrimitive?.content ?: result["url"]?.jsonPrimitive?.content ?: continue
            val uri = Url(url)
            require(uri.protocol.name in setOf("https", "http") && uri.user.isNullOrEmpty() && uri.password.isNullOrEmpty())
            val type = result["mimeType"]?.jsonPrimitive?.content ?: when (result["streamType"]?.jsonPrimitive?.content) {
                "hls" -> "application/x-mpegURL"; "dash" -> "application/dash+xml"; else -> "application/octet-stream"
            }
            if (type == "application/dash+xml" || result["inlineManifest"] != null) continue
            val headers = (result["headers"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
            val stream = DirectStream(url, type, "", null, module.name, source, track.title,
                track.artists.joinToString(", "), track.album, track.durationMs,
                headers = moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders.of(headers))
            return@withLock PreparedAudio.Progressive(stream)
        }
        null
    }

    private suspend fun call(module: IosModuleInfo, function: String, args: JsonArray): JsonElement = withContext(dispatcher) {
        val runtime = QuickJs.create(dispatcher)
        val timers = mutableMapOf<String, CompletableDeferred<Unit>>()
        val requestLock = NSLock()
        val requests = mutableMapOf<String, Job>()
        try {
            runtime.memoryLimit = 32L * 1024 * 1024; runtime.maxStackSize = 1024 * 1024; runtime.evaluationTimeoutMillis = 15_000
            runtime.asyncFunction<String, String>("__moduleFetch") { input ->
                val id = Json.parseToJsonElement(input).jsonObject.getValue("id").jsonPrimitive.content
                val job = requireNotNull(currentCoroutineContext()[Job])
                requestLock.locked { requests[id] = job }
                try { fetch(input) } finally { requestLock.locked { requests.remove(id) } }
            }
            runtime.function("__moduleTimerCreate") { values -> require(timers.size < 128); timers[values.first().toString()] = CompletableDeferred<Unit>(); Unit }
            runtime.asyncFunction<String, Unit>("__moduleDelay") { input -> val data = Json.parseToJsonElement(input).jsonObject
                withTimeoutOrNull((data["millis"]?.jsonPrimitive?.longOrNull ?: 0L).coerceIn(1L, 15_000L)) {
                    timers[data.getValue("id").jsonPrimitive.content]?.await()
                }; Unit }
            runtime.function("__moduleClearTimer") { values -> timers.remove(values.first().toString())?.complete(Unit); Unit }
            runtime.function("__moduleAbort") { values -> requestLock.locked { requests.remove(values.first().toString()) }?.cancel(); Unit }
            runtime.function("__moduleUrl") { values ->
                val url = URLBuilder(values.getOrNull(1)?.toString()?.takeUnless { it == "undefined" } ?: "https://example.invalid/")
                    .apply { takeFrom(values.first().toString()) }.build()
                buildJsonObject { put("href", url.toString()); put("protocol", url.protocol.name + ":"); put("hostname", url.host)
                    put("host", url.host); put("pathname", url.encodedPath); put("search", url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty())
                    put("hash", url.fragment.takeIf { it.isNotEmpty() }?.let { "#$it" }.orEmpty()); put("origin", "${url.protocol.name}://${url.host}") }.toString()
            }
            val hostPath = requireNotNull(NSBundle.mainBundle.pathForResource("ModuleHost", "js"))
            val host = readPortableFileBytes(hostPath, 1024 * 1024L).decodeToString()
            val stored = Json.parseToJsonElement(readPortableFileBytes(path(module.id), 5L * 1024 * 1024).decodeToString()).jsonObject
            val code = stored.getValue("code").jsonPrimitive.content.replaceFirst(Regex("^export\\s+default\\s+"), "return ")
            withTimeout(15_000L) {
                runtime.evaluate<String>(host + "\n;\"ready\";", "module-host.js")
                runtime.evaluate<String>("globalThis.__provider=(function(){const module={exports:{}};const exports=module.exports;\n" + code + "\n;return module.exports;})();\"ready\";", "module-provider.js")
                runtime.evaluate<String>("globalThis.__moduleResult=null;(async()=>{try{globalThis.__moduleResult=JSON.stringify({value:await globalThis.__provider[" +
                    JsonPrimitive(function) + "](...JSON.parse(" + JsonPrimitive(args.toString()) + "))});}catch(_){globalThis.__moduleResult=JSON.stringify({failed:true});}})();\"started\";", "module-call.js")
                val raw = runtime.evaluate<String>("globalThis.__moduleResult||''", "module-result.js")
                require(raw.length <= 2 * 1024 * 1024)
                val result = Json.parseToJsonElement(raw).jsonObject
                require(result["failed"]?.jsonPrimitive?.booleanOrNull != true) { "Module execution failed" }
                result.getValue("value")
            }
        } finally { timers.values.forEach { it.complete(Unit) }; runtime.close() }
    }
    fun close() { http.close() }

    private suspend fun fetch(input: String): String {
        val data = Json.parseToJsonElement(input).jsonObject
        val url = Url(data.getValue("url").jsonPrimitive.content)
        require(url.protocol.name in setOf("https", "http") && url.user.isNullOrEmpty() && url.password.isNullOrEmpty())
        return withTimeout(12_000L) { http.prepareRequest(url.toString()) {
            method = HttpMethod(data["method"]?.jsonPrimitive?.content ?: "GET")
            (data["headers"] as? JsonObject).orEmpty().forEach { (name, value) -> header(name, value.jsonPrimitive.content) }
            data["body"]?.jsonPrimitive?.content?.let { setBody(it) }
            data["bodyBase64"]?.jsonPrimitive?.content?.let { setBody(Base64.decode(it)) }
        }.execute { response ->
            val chunks = mutableListOf<ByteArray>(); val buffer = ByteArray(8192); var total = 0
            val channel = response.bodyAsChannel()
            try { while (true) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                if (read == 0) { yield(); continue }
                require(total <= 2 * 1024 * 1024 - read)
                chunks.add(buffer.copyOf(read)); total += read
            } } finally { channel.cancel(null) }
            val bytes = ByteArray(total); var offset = 0
            for (chunk in chunks) { chunk.copyInto(bytes, offset); offset += chunk.size }
            buildJsonObject { put("status", response.status.value); put("ok", response.status.isSuccess()); put("url", url.toString())
                put("text", bytes.decodeToString()); put("base64", Base64.encode(bytes))
                put("headers", buildJsonObject { response.headers.entries().forEach { (name, value) -> put(name, value.joinToString(", ")) } }) }.toString()
        } }
    }
}

private inline fun <T> NSLock.locked(block: () -> T): T {
    lock()
    try { return block() } finally { unlock() }
}
