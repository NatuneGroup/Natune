package moe.rukamori.archivetune.shared.data.modules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.*

class IosModulePolicyTest {
    private fun metadata(features: JsonElement) = buildJsonObject {
        put("id", "test.audio"); put("name", "Audio"); put("version", "1.0.0")
        put("type", "audio_provider")
        put("capabilities", buildJsonArray { add("searchTracks"); add("getTrackStreamUrl") })
        put("requiredRuntimeFeatures", features)
    }

    @Test fun scalarApiV1FeatureDeclarationsAreChecked() {
        validateModuleMetadata(metadata(JsonPrimitive("fetch@1")))
        val error = assertFailsWith<IosModuleFailure> {
            validateModuleMetadata(metadata(JsonPrimitive("signedSession@1")))
        }
        assertEquals(IosModuleIssue.UNSUPPORTED_CONTRACT, error.issue)
    }

    @Test fun providerSecondsAreConvertedForTrackMatching() {
        assertEquals(120_000L, moduleDurationMs(buildJsonObject { put("duration", 120) }))
        assertEquals(120_500L, moduleDurationMs(buildJsonObject { put("duration", 120.5) }))
        assertEquals(121_000L, moduleDurationMs(buildJsonObject { put("duration", 120); put("durationMs", 121_000) }))
        assertNull(moduleDurationMs(buildJsonObject { put("duration", -1) }))
        assertNull(moduleDurationMs(buildJsonObject { put("duration", 1_000_000) }))
        assertNull(moduleDurationMs(JsonObject(emptyMap())))
    }
}
