@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.modules

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val root: String,
    private val credentials: SecureCredentialStore,
    private val preferences: () -> PreferenceSnapshot?,
) : AudioProviderRepository {
    override val source = AudioSourceType.MODULES
    private val http = HttpClient(Darwin) { followRedirects = false; expectSuccess = false }
    private val lock = Mutex()
    private val manager = NSFileManager.defaultManager
    private val issues = MutableStateFlow<Map<String, IosModuleIssue>>(emptyMap())
    val status = issues.asStateFlow()
    private fun key(id: String) = CredentialKey("modules", CredentialScope.PERSONAL, id)
    private fun path(id: String): String { require(id.matches(Regex("^[A-Za-z0-9._-]{1,128}$")) && id != ".." && id != "."); return "$root/$id.json" }
    fun installed(): List<IosModuleInfo> = manager.contentsOfDirectoryAtPath(root, null).orEmpty()
        .filterIsInstance<String>().filter { it.endsWith(".json") }.sorted().take(32).mapNotNull { name ->
            try { val data = Json.parseToJsonElement(readPortableFileBytes("$root/$name", 5L * 1024 * 1024).decodeToString()).jsonObject
                val meta = validateModuleMetadata(data.getValue("metadata").jsonObject)
                IosModuleInfo(meta.getValue("id").jsonPrimitive.content.also { moduleRequire(path(it) == "$root/$name") }, meta.getValue("name").jsonPrimitive.content, meta)
            } catch (_: Exception) { null }
        }
    suspend fun install(bytes: ByteArray): IosModuleInfo = withContext(Dispatchers.Default) { lock.withLock {
        moduleRequire(bytes.size in 1..4 * 1024 * 1024)
        val entries = PortableZip.read(bytes, 3, { name -> when (name) {
            "manifest.json" -> 128 * 1024L; "provider.js" -> 4 * 1024 * 1024L; "icon.png" -> 256 * 1024L
            else -> throw IosModuleFailure(IosModuleIssue.UNSUPPORTED_CONTRACT)
        } }, 5 * 1024 * 1024L)
        require(entries.distinctBy { it.name }.size == entries.size)
        val files = entries.associate { it.name to it.data }
        val manifest = Json.parseToJsonElement(requireNotNull(files["manifest.json"]).decodeToString()).jsonObject
        require(manifest["format"]?.jsonPrimitive?.content == "archivetune.module" &&
            manifest["formatVersion"]?.jsonPrimitive?.int == 1 && manifest["apiVersion"]?.jsonPrimitive?.int == 1 &&
            manifest["entry"]?.jsonPrimitive?.content == "provider.js")
        moduleRequire(manifest["runtime"]?.jsonPrimitive?.content in setOf(null, "archivetune"), IosModuleIssue.UNSUPPORTED_CONTRACT)
        val script = requireNotNull(files["provider.js"])
        require(ContentDigest.sha256(script).equals(manifest["sha256"]?.jsonPrimitive?.content, true)) { "Module checksum mismatch" }
        val code = script.decodeToString(throwOnInvalidSequence = true)
        require(!code.trimStart().startsWith("8SM1.")) { "Encrypted module envelope unsupported" }
        val metadata = validateModuleMetadata(manifest.getValue("module").jsonObject)
        val id = metadata.getValue("id").jsonPrimitive.content
        path(id)
        val icon = files["icon.png"]
        moduleRequire((icon != null) == (metadata["icon"] != null))
        if (icon != null) {
            moduleRequire(metadata["icon"]?.jsonPrimitive?.content == "icon.png" &&
                ContentDigest.sha256(icon).equals(manifest["iconSha256"]?.jsonPrimitive?.content, true))
        }
        val stored = buildJsonObject { put("metadata", metadata); put("code", code) }.toString().encodeToByteArray()
        moduleRequire(stored.size <= 5 * 1024 * 1024)
        manager.createDirectoryAtPath(root, true, null, null)
        moduleRequire(manager.contentsOfDirectoryAtPath(root, null).orEmpty().filterIsInstance<String>().count { it.endsWith(".json") } < 32 || manager.fileExistsAtPath(path(id)))
        val target = path(id)
        val temporary = "$target.tmp"
        try {
            writePortableFileBytes(temporary, stored)
            check(rename(temporary, target) == 0) { "Cannot commit module package" }
        } finally { manager.removeItemAtPath(temporary, null) }
        issues.value = issues.value - id
        IosModuleInfo(id, metadata.getValue("name").jsonPrimitive.content, metadata)
    } }

    suspend fun configure(id: String, json: String) = lock.withLock {
        moduleRequire(json.encodeToByteArray().size <= 64 * 1024, IosModuleIssue.INVALID_CONFIGURATION)
        val module = installed().firstOrNull { it.id == id } ?: throw IosModuleFailure(IosModuleIssue.INVALID_PACKAGE)
        moduleSettings(module.metadata, Json.parseToJsonElement(json).jsonObject)
        credentials.write(key(id), json)
        issues.value = issues.value - id
    }
    suspend fun configuration(id: String): String { path(id); return credentials.read(key(id)) ?: "{}" }
    suspend fun remove(id: String) = lock.withLock {
        check(manager.removeItemAtPath(path(id), null))
        credentials.remove(key(id)); issues.value = issues.value - id
    }

    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? = lock.withLock {
        if (preferences()?.get(ModulesEnabledKey) != true) return@withLock null
        // A bad package, expired credential or unsupported stream stands down for the next module/source.
        for (module in installed()) {
            try {
                val prepared = resolveModule(module, request)
                issues.value = issues.value - module.id
                if (prepared != null) return@withLock prepared
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                issues.value = issues.value + (module.id to ((error as? IosModuleFailure)?.issue ?: IosModuleIssue.EXECUTION_FAILED))
            }
        }
        null
    }

    private suspend fun resolveModule(module: IosModuleInfo, request: AudioResolveRequest): PreparedAudio? {
        val values = Json.parseToJsonElement(configuration(module.id)).jsonObject
        val settings = moduleSettings(module.metadata, values)
        if ((settings["enabled"] as? JsonObject)?.get("value")?.jsonPrimitive?.booleanOrNull == false) return null
        val context = buildJsonObject { put("settings", settings); put("track", buildJsonObject {
            put("id", request.mediaId); put("title", request.target.title)
            put("artist", request.target.artists.joinToString(", "))
        }) }
        val found = call(module, "searchTracks", buildJsonArray {
            add(JsonPrimitive((listOf(request.target.title) + request.target.artists).joinToString(" ")))
            add(JsonPrimitive(10)); add(context)
        })
        val rows = (found as? JsonArray) ?: (found as? JsonObject)?.get("tracks") as? JsonArray ?: return null
        moduleRequire(rows.size <= 100, IosModuleIssue.EXECUTION_FAILED)
        val tracks = rows.mapNotNull { value -> (value as? JsonObject)?.let { row ->
            val id = row["id"]?.jsonPrimitive?.content ?: return@let null
            val title = row["title"]?.jsonPrimitive?.content ?: row["name"]?.jsonPrimitive?.content ?: return@let null
            val artists = (row["artists"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }
                ?: listOfNotNull(row["artist"]?.jsonPrimitive?.contentOrNull)
            TrackMatching.Candidate(id, title, artists, row["album"]?.jsonPrimitive?.contentOrNull, row["durationMs"]?.jsonPrimitive?.longOrNull)
        } }
        val track = TrackMatching.best(request.target, tracks) ?: return null
        val result = call(module, "getTrackStreamUrl", buildJsonArray {
            add(JsonPrimitive(track.id)); add((settings["quality"] as? JsonObject)?.get("value") ?: JsonNull); add(context)
        }) as? JsonObject ?: return null
        val url = result["streamUrl"]?.jsonPrimitive?.content ?: result["url"]?.jsonPrimitive?.content
        val streamType = result["streamType"]?.jsonPrimitive?.content.orEmpty()
        moduleRequire(url != null && streamType !in setOf("dash", "drm", "encrypted") &&
            listOf("inlineManifest", "manifest", "drm", "licenseUrl", "encryption").none { it in result }, IosModuleIssue.UNSUPPORTED_STREAM)
        val uri = Url(requireNotNull(url))
        moduleRequire(uri.protocol.name in setOf("https", "http") && uri.user.isNullOrEmpty() && uri.password.isNullOrEmpty(), IosModuleIssue.UNSUPPORTED_STREAM)
        val type = result["mimeType"]?.jsonPrimitive?.content ?: if (streamType == "hls") "application/x-mpegURL" else "application/octet-stream"
        moduleRequire(type != "application/dash+xml", IosModuleIssue.UNSUPPORTED_STREAM)
        val headers = (result["headers"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
        moduleRequire(headers.size <= 64 && headers.all { (k, v) -> k.length <= 256 && v.length <= 8192 && !k.contains('\n') && !v.contains('\n') && !k.contains('\r') && !v.contains('\r') }, IosModuleIssue.UNSUPPORTED_STREAM)
        return PreparedAudio.Progressive(DirectStream(url, type, "", null, module.name, source, track.title,
            track.artists.joinToString(", "), track.album, track.durationMs,
            headers = moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders.of(headers)))
    }

    private suspend fun call(module: IosModuleInfo, function: String, args: JsonArray): JsonElement {
        val worker = newSingleThreadContext("ios-module")
        try { return withContext(worker) {
        val runtime = QuickJs.create(worker)
        val timers = mutableMapOf<String, CompletableDeferred<Unit>>()
        val requestLock = NSLock()
        val requests = mutableMapOf<String, Job>()
        val network = Semaphore(4)
        var requestCount = 0
        try {
            runtime.memoryLimit = 32L * 1024 * 1024; runtime.maxStackSize = 1024 * 1024; runtime.evaluationTimeoutMillis = 15_000
            runtime.asyncFunction<String, String>("__moduleFetch") { input ->
                val id = Json.parseToJsonElement(input).jsonObject.getValue("id").jsonPrimitive.content
                val job = requireNotNull(currentCoroutineContext()[Job])
                requestLock.locked { moduleRequire(++requestCount <= 32, IosModuleIssue.EXECUTION_FAILED); requests[id] = job }
                try { network.withPermit { fetch(input) } } finally { requestLock.locked { requests.remove(id) } }
            }
            runtime.function("__moduleTimerCreate") { values -> require(timers.size < 128); timers[values.first().toString()] = CompletableDeferred<Unit>(); Unit }
            runtime.asyncFunction<String, Unit>("__moduleDelay") { input -> val data = Json.parseToJsonElement(input).jsonObject
                withTimeoutOrNull((data["millis"]?.jsonPrimitive?.longOrNull ?: 0L).coerceIn(1L, 15_000L)) {
                    timers[data.getValue("id").jsonPrimitive.content]?.await()
                }; Unit }
            runtime.function("__moduleClearTimer") { values -> timers.remove(values.first().toString())?.complete(Unit); Unit }
            runtime.function("__moduleAbort") { values -> requestLock.locked { requests.remove(values.first().toString()) }?.cancel(); Unit }
            runtime.function("__moduleUrl") { values ->
                val base = values.getOrNull(1)?.toString()?.takeUnless { it == "undefined" }
                val raw = values.first().toString()
                val resolved = platform.Foundation.NSURL.URLWithString(raw, base?.let { platform.Foundation.NSURL.URLWithString(it) })?.absoluteURL?.absoluteString
                val url = Url(requireNotNull(resolved))
                buildJsonObject { put("href", url.toString()); put("protocol", url.protocol.name + ":"); put("hostname", url.host)
                    put("host", url.host + if (url.port != url.protocol.defaultPort) ":${url.port}" else ""); put("pathname", url.encodedPath); put("search", url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty())
                    put("hash", url.fragment.takeIf { it.isNotEmpty() }?.let { "#$it" }.orEmpty()); put("origin", "${url.protocol.name}://${url.host}" + if (url.port != url.protocol.defaultPort) ":${url.port}" else "") }.toString()
            }
            val hostPath = requireNotNull(NSBundle.mainBundle.pathForResource("ModuleHost", "js"))
            val host = readPortableFileBytes(hostPath, 1024 * 1024L).decodeToString()
            val stored = Json.parseToJsonElement(readPortableFileBytes(path(module.id), 5L * 1024 * 1024).decodeToString()).jsonObject
            val code = stored.getValue("code").jsonPrimitive.content.removePrefix("\uFEFF")
            withTimeout(15_000L) {
                runtime.evaluate<String>("Object.defineProperty(globalThis, \"Atomics\", {value:undefined});Object.defineProperty(globalThis, \"SharedArrayBuffer\", {value:undefined});\n" + host + "\n;\"ready\";", "module-host.js")
                runtime.evaluate<String>("globalThis.__provider=(function(){const module={exports:{}};const exports=module.exports;\n" + code + "\n;const p=module.exports; if(!p || typeof p.searchTracks!==\"function\" || typeof p.getTrackStreamUrl!==\"function\")throw new Error(\"Invalid provider\");return p;})();\"ready\";", "module-provider.js")
                runtime.evaluate<String>("globalThis.__moduleResult=null;(async()=>{try{const args=JSON.parse(" + JsonPrimitive(args.toString()) +
                    ");if(args[1]===null)args[1]=undefined;const value=await globalThis.__provider[" + JsonPrimitive(function) +
                    "](...args);const result=JSON.stringify({value:value===undefined?null:value});if(result.length>2097152)throw new Error();globalThis.__moduleResult=result;}" +
                    "catch(error){globalThis.__moduleResult=JSON.stringify({failed:true,configuration:error?.code===\"VERIFY_REQUIRED\"});}finally{globalThis.__moduleClearTimers();}})();\"started\";", "module-call.js")
                val raw = runtime.evaluate<String>("globalThis.__moduleResult||''", "module-result.js")
                require(raw.length <= 2 * 1024 * 1024)
                val result = Json.parseToJsonElement(raw).jsonObject
                if (result["failed"]?.jsonPrimitive?.booleanOrNull == true) throw IosModuleFailure(
                    if (result["configuration"]?.jsonPrimitive?.booleanOrNull == true) IosModuleIssue.INVALID_CONFIGURATION else IosModuleIssue.EXECUTION_FAILED)
                result.getValue("value")
            }
        } finally { withContext(NonCancellable) {
            requestLock.locked { requests.values.toList() }.forEach { it.cancel() }
            timers.values.forEach { it.complete(Unit) }; runtime.close()
        } }
        } } finally { worker.close() }
    }
    fun close() { http.close() }

    private suspend fun fetch(input: String): String {
        moduleRequire(input.length <= 3 * 1024 * 1024, IosModuleIssue.EXECUTION_FAILED)
        val data = Json.parseToJsonElement(input).jsonObject
        val url = Url(data.getValue("url").jsonPrimitive.content)
        require(url.protocol.name in setOf("https", "http") && url.user.isNullOrEmpty() && url.password.isNullOrEmpty())
        return withTimeout(12_000L) { http.prepareRequest(url.toString()) {
            method = HttpMethod(data["method"]?.jsonPrimitive?.content ?: "GET")
            (data["headers"] as? JsonObject).orEmpty().forEach { (name, value) -> header(name, value.jsonPrimitive.content) }
            data["body"]?.jsonPrimitive?.content?.let { moduleRequire(it.encodeToByteArray().size <= 2 * 1024 * 1024, IosModuleIssue.EXECUTION_FAILED); setBody(it) }
            data["bodyBase64"]?.jsonPrimitive?.content?.let { val body = Base64.decode(it); moduleRequire(body.size <= 2 * 1024 * 1024, IosModuleIssue.EXECUTION_FAILED); setBody(body) }
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
