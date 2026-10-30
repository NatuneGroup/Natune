package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

class PersonalMixPlanTest {
    private fun song(id: String) =
        ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, id), "Song $id", "Artist $id")

    @Test
    fun plainAndFencedResponsesParseTheSame() {
        val body = """{"mixes":[{"title":"Focus Mix","description":"Calm","songIds":["a"],"recommendations":[]}]}"""
        val plain = parsePersonalMixPlan(body)
        assertEquals(plain, parsePersonalMixPlan("```json\n$body\n```"))
        val plan = plain.single()
        assertEquals("Focus Mix", plan.title)
        assertEquals("Calm", plan.description)
        assertEquals(listOf("a"), plan.songIds)
    }

    @Test
    fun malformedResponsesFailInsteadOfParsingPartially() {
        assertFailsWith<IllegalStateException> { parsePersonalMixPlan("[]") }
        assertFailsWith<IllegalStateException> { parsePersonalMixPlan("{}") }
        assertFailsWith<Exception> { parsePersonalMixPlan("not json") }
        assertFailsWith<IllegalArgumentException> { parsePersonalMixPlan(" ".repeat(129 * 1024)) }
    }

    @Test
    fun malformedRowsAreSkippedNotTrusted() {
        val plans = parsePersonalMixPlan(
            """{"mixes":["junk",{"title":"","songIds":["a"]},""" +
                """{"title":"Real","songIds":["a",7,null,"a","","b"],""" +
                """"recommendations":[{"title":"T","artist":"A"},{"title":"T"},{"title":5,"artist":"A"}]}]}""",
        )
        val plan = plans.single()
        assertEquals("Real", plan.title)
        assertEquals(listOf("a", "b"), plan.songIds)
        assertEquals(listOf(PersonalMixRecommendation("T", "A")), plan.recommendations)
    }

    @Test
    fun capsBoundMixesSongsAndIdLength() {
        val mixes = (1..8).joinToString(",") { mix ->
            val ids = (1..60).joinToString(",") { "\"s$it\"" }
            """{"title":"M$mix","songIds":[$ids]}"""
        }
        val plans = parsePersonalMixPlan("""{"mixes":[$mixes]}""")
        assertEquals(PERSONAL_MIX_LIMIT, plans.size)
        assertEquals(PERSONAL_MIX_SONG_LIMIT, plans[0].songIds.size)
        val longIds = parsePersonalMixPlan("""{"mixes":[{"title":"M","songIds":["${"x".repeat(200)}","ok"]}]}""")
        assertEquals(listOf("ok"), longIds.single().songIds)
    }

    @Test
    fun bindingKeepsOnlyVerifiedIdentitiesAndDropsEmptyMixes() {
        val candidate = song("a")
        val verified = PersonalMixRecommendation("Rec", "Artist") to song("r")
        val plans = listOf(
            PersonalMixPlan(
                "Mix", "d", listOf("a", "missing"),
                listOf(verified.first, PersonalMixRecommendation("X", "Y")),
            ),
            PersonalMixPlan("Empty", "", listOf("unknown"), emptyList()),
        )
        val bound = bindPersonalMixPlan(plans, listOf(candidate), mapOf(verified))
        assertEquals(1, bound.size)
        assertEquals("Mix", bound[0].title)
        assertEquals(listOf("a", "r"), bound[0].songs.map { it.ref.id })
    }

    @Test
    fun bindingDeduplicatesAcrossIdsAndRecommendations() {
        val shared = song("s")
        val plans = listOf(PersonalMixPlan("Mix", "", listOf("s"), listOf(PersonalMixRecommendation("R", "A"))))
        val bound = bindPersonalMixPlan(plans, listOf(shared), mapOf(PersonalMixRecommendation("R", "A") to shared))
        assertEquals(listOf("s"), bound.single().songs.map { it.ref.id })
    }
}
