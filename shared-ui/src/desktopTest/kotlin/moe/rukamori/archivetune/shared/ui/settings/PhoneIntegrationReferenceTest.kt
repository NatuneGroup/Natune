package moe.rukamori.archivetune.shared.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import moe.rukamori.archivetune.shared.ui.resources.*

class PhoneIntegrationReferenceTest {
    @Test fun spotifyCatalogRowFillsTheExternalSourcesSlot() {
        val catalog = SettingsRow.Action("spotify", Res.string.m6_source_spotify_catalog, run = {})
        val entries = phoneSettingsEntries(listOf(catalog), PhoneIntegrationScreenReference)
        val slot = entries.indexOfFirst { it.reference?.anchor == "spotify_account" }
        assertSame(catalog, entries[slot].row)
        assertEquals("group:external_sources", entries[slot - 1].reference?.anchor)
        assertEquals(1, entries.count { it.row === catalog })
    }
}
