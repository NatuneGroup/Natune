package moe.rukamori.archivetune.modules

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModuleParsingTest {
    private fun metadata() = JSONObject().put("id", "app.natune.test").put("name", "Test").put("version", "1.0.0")
        .put("capabilities", JSONArray(listOf("searchTracks", "getTrackStreamUrl")))

    private fun archive(script: String, metadata: JSONObject = metadata(), digest: String? = null): ByteArray {
        val source = script.toByteArray()
        val checksum = digest ?: MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it.toInt() and 255) }
        val manifest = JSONObject().put("format", "archivetune.module").put("formatVersion", 1).put("apiVersion", 1)
            .put("entry", "provider.js").put("sha256", checksum).put("module", metadata).toString().toByteArray()
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            listOf("manifest.json" to manifest, "provider.js" to source).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        } }.toByteArray()
    }

    @Test fun encryptedEnvelopeIsRejectedWithoutDecryption() {
        for (source in listOf("8SM1.anything", "\uFEFF 8SM1.anything", "/* comment */ 8SM1.anything", "export const payload = `8SM1.anything`;")) {
            val failure = assertThrows(ModuleException::class.java) { ModuleSourceDecoder.decode(source) }
            assertEquals(ModuleFailure.UNSUPPORTED, failure.failure)
            assertEquals("ENCRYPTED_8SM1_REJECTED", failure.diagnosticCode)
        }
    }

    @Test fun readableTextModuleStillLoads() {
        assertEquals("module.exports = {};", ModuleSourceDecoder.decode("/* license */\nmodule.exports = {};"))
    }

    @Test fun manifestValidationRetainsStrictSyntaxAndDepthLimits() {
        for (source in listOf("{'id':'test'}", "{\"id\":\"test\"} {}", "{\"value\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}")) {
            val failure = assertThrows(ModuleException::class.java) { ModulePackageReader.manifest(source.toByteArray()) }
            assertEquals(ModuleFailure.INVALID_DOCUMENT, failure.failure)
        }
        assertEquals("test", ModulePackageReader.manifest("{\"id\":\"test\"}".toByteArray()).getString("id"))
    }

    @Test fun compatiblePackageRetainsTypedSetting() = runBlocking {
        val data = metadata().put("settings", JSONObject().put("country", JSONObject().put("type", "text").put("defaultValue", "US")))
        val normalized = ModulePackageReader.read(archive("module.exports = {searchTracks() {}, getTrackStreamUrl() {}};", data), ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION)
        assertTrue(normalized.contains("app.natune.test"))
        val manifest = ModuleManifest.parse(data)
        assertEquals("\"US\"", manifest.settings.single().defaultJson)
    }

    @Test fun wrongChecksumIsRejectedBeforeExecution() {
        val failure = assertThrows(ModuleException::class.java) { runBlocking {
            ModulePackageReader.read(archive("module.exports = {};", digest = "0".repeat(64)), ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION)
        } }
        assertEquals(ModuleFailure.INTEGRITY, failure.failure)
    }

    @Test fun upstreamAudioTypesAndCapabilitiesAreUnchanged() = runBlocking {
        for (types in listOf(emptyList(), listOf("audio_provider"), listOf("music", "lossless"),
            listOf("lyrics_provider"), listOf("metadata_provider"), listOf("playback_provider"),
            listOf("audio_provider", "lyrics_provider", "metadata_provider"))) {
            val data = metadata().put("type", JSONArray(types))
            val manifest = ModuleManifest.parse(data)
            assertEquals(ModuleKind.AUDIO_SOURCE, manifest.kind)
            assertEquals(types, manifest.types)
            assertEquals(listOf("searchTracks", "getTrackStreamUrl"), manifest.capabilities)
            assertTrue(ModulePackageReader.read(archive("module.exports = {searchTracks() {}, getTrackStreamUrl() {}};", data),
                ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION).contains("Provider capability mismatch"))
        }
    }

    @Test fun natuneKindsParseWithoutAudioRequirements() = runBlocking {
        for ((kind, methods) in listOf(ModuleKind.METADATA to listOf("getHome", "getLibrary", "searchTracks"),
            ModuleKind.LYRICS to listOf("getLyrics"), ModuleKind.PLAYBACK to listOf("searchTracks", "getTrackStreamUrl"))) {
            val data = metadata().put("type", kind.wireType).put("capabilities", JSONArray(methods))
                .put("requiredRuntimeFeatures", JSONArray(listOf("natuneExtensions@1")))
            val manifest = ModuleManifest.parse(data)
            manifest.requireCompatible(ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION)
            assertEquals(kind, manifest.kind)
            val script = "module.exports = {" + methods.joinToString(",") { "$it() {}" } + "};"
            assertTrue(ModulePackageReader.read(archive(script, data), ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION).contains(kind.wireType))
        }
    }

    @Test fun ambiguousKindsAndWrongCapabilitiesAreRejected() {
        fun natuneMetadata() = metadata().put("requiredRuntimeFeatures", JSONArray(listOf("natuneExtensions@1")))
        assertThrows(ModuleException::class.java) { ModuleManifest.parse(natuneMetadata().put("type", JSONArray(listOf("lyrics_provider", "metadata_provider")))) }
        assertThrows(ModuleException::class.java) { ModuleManifest.parse(natuneMetadata().put("type", "lyrics_provider")) }
        assertThrows(ModuleException::class.java) { ModuleManifest.parse(natuneMetadata().put("type", "metadata_provider").put("capabilities", JSONArray(listOf("getLyrics")))) }
    }

    @Test fun compatibilityFloorUsesUpstreamContractAndRejectsFutureRequirements() {
        ModuleManifest.parse(metadata().put("minAppVersion", "15.1.0")).requireCompatible(ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION)
        val failure = assertThrows(ModuleException::class.java) {
            ModuleManifest.parse(metadata().put("minAppVersion", "15.2.0")).requireCompatible(ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION)
        }
        assertEquals("ARCH_APP_VERSION", failure.diagnosticCode)
    }

    @Test fun unknownRuntimeFeaturesAreRejected() {
        val failure = assertThrows(ModuleException::class.java) {
            ModuleManifest.parse(metadata().put("requiredRuntimeFeatures", JSONArray(listOf("unknown@9"))))
                .requireCompatible(ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION)
        }
        assertEquals("ARCH_RUNTIME_FEATURE", failure.diagnosticCode)
    }

    @Test fun upstreamCatalogNamesAndRelativeUrlsArePreserved() = runBlocking {
        val root = JSONObject().put("name", "ArchiveTune Modules").put("extensions", JSONArray().put(JSONObject()
            .put("id", "moe.rukamori.provider.tidal").put("name", "tidal").put("display_name", "TIDAL")
            .put("download_url", "dist/tidal.arch").put("sha256", "a".repeat(64))))
        val catalog = ModuleCatalogReader(ModuleHttpClient(OkHttpClient())).read("https://example.org/registry.json", root.toString())
        assertEquals("TIDAL", catalog.entries.single().name)
        assertEquals("https://example.org/dist/tidal.arch", catalog.entries.single().downloadUrl)
        assertEquals("a".repeat(64), catalog.entries.single().sha256)
    }
}
