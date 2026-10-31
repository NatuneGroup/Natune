package moe.rukamori.archivetune.shared.data.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.rukamori.archivetune.shared.domain.repository.ContentItem

data class PersonalMixRecommendation(val title: String, val artist: String)
data class PersonalMixPlan(
    val title: String,
    val description: String,
    val songIds: List<String>,
    val recommendations: List<PersonalMixRecommendation>,
)
data class GeneratedPersonalMix(val title: String, val description: String, val songs: List<ContentItem>)

/** A completion is a plan, never trusted provider identities or a partially parsed success. */
fun parsePersonalMixPlan(response: String): List<PersonalMixPlan> {
    require(response.length <= 128 * 1024) { "Mix response exceeded its bound" }
    val text = response.trim().let {
        if (it.startsWith("```") && it.endsWith("```")) it.substringAfter('\n').substringBeforeLast("```").trim() else it
    }
    val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Missing mix object")
    val mixes = root["mixes"] as? JsonArray ?: error("Missing mixes")
    return mixes.take(PERSONAL_MIX_LIMIT).mapNotNull { element ->
        val mix = element as? JsonObject ?: return@mapNotNull null
        val title = mix.string("title")?.lineSequence()?.firstOrNull()?.trim()?.take(80)
            ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val description = mix.string("description")?.lineSequence()?.firstOrNull()?.trim()?.take(300).orEmpty()
        val ids = (mix["songIds"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.trim() }
            .filter { it.isNotBlank() && it.length <= 128 }
            .distinct().take(PERSONAL_MIX_SONG_LIMIT)
        val recommendations = (mix["recommendations"] as? JsonArray).orEmpty().mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val song = item.string("title")?.trim()?.take(160)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val artist = item.string("artist")?.trim()?.take(160)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            PersonalMixRecommendation(song, artist)
        }.distinct().take(PERSONAL_MIX_SONG_LIMIT)
        PersonalMixPlan(title, description, ids, recommendations)
    }
}

fun bindPersonalMixPlan(
    plans: List<PersonalMixPlan>,
    candidates: List<ContentItem>,
    recommendations: Map<PersonalMixRecommendation, ContentItem>,
): List<GeneratedPersonalMix> {
    val known = candidates.associateBy { it.ref.id }
    return plans.take(PERSONAL_MIX_LIMIT).mapNotNull { plan ->
        val songs = (plan.songIds.mapNotNull(known::get) + plan.recommendations.mapNotNull(recommendations::get))
            .distinctBy { it.ref }.take(PERSONAL_MIX_SONG_LIMIT)
        if (songs.isEmpty()) null else GeneratedPersonalMix(plan.title, plan.description, songs)
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

const val PERSONAL_MIX_LIMIT = 5
const val PERSONAL_MIX_SONG_LIMIT = 50
