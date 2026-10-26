/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.persistence

import kotlin.test.*

class PlaylistExchangeTest {
    private val rows = listOf(
        PlaylistExchangeTrack("=SUM(1,2)", "Artist; Other", "Album\nTitle", "id-a", "file:///song-a", 100),
        PlaylistExchangeTrack("日本語 \"曲\"", "عربي", "", "id-a", "file:///song-a", null),
    )
    @Test fun csvMatchesShippedBomQuotedCrLfAndFormulaEscaping() {
        val csv = PlaylistExchange.csv(rows)
        assertTrue(csv.startsWith("\uFEFF\"Title\",\"Artist\",\"Album\",\"Media ID\"\r\n"))
        assertTrue(csv.contains("\"'=SUM(1,2)\""))
        val imported = PlaylistExchange.readCsv(csv)
        assertEquals(listOf("'=SUM(1,2)", rows[1].title), imported.map { it.title })
        assertEquals(rows.map { it.mediaId }, imported.map { it.mediaId })
    }
    @Test fun jsonKeepsDuplicateRowsAndTags() {
        val archive = PlaylistExchange.json("Mix", rows, setOf("favorite", "日本語"))
        val imported = PlaylistExchange.readJson(archive)
        assertEquals(rows, imported.tracks)
        assertEquals(setOf("favorite", "日本語"), imported.tags)
    }
    @Test fun m3uKeepsDurationsAndDoesNotInjectExtraLines() {
        val m3u = PlaylistExchange.m3u(rows)
        assertTrue(m3u.startsWith("#EXTM3U\n"))
        assertEquals(2, PlaylistExchange.readM3u(m3u).size)
        assertFailsWith<IllegalArgumentException> { PlaylistExchange.m3u(rows.map { it.copy(uri = "file:///one\nfile:///two") }) }
    }
    @Test fun emptyAndMalformedImportsFailRatherThanSucceedWithNothing() {
        assertFailsWith<IllegalArgumentException> { PlaylistExchange.readCsv("Title,Artist,Album,Media ID\n") }
        assertFailsWith<IllegalArgumentException> { PlaylistExchange.readJson("""{"title":"empty","tracks":[]}""") }
        assertFailsWith<IllegalArgumentException> { PlaylistExchange.readM3u("#EXTM3U\n") }
    }
}
