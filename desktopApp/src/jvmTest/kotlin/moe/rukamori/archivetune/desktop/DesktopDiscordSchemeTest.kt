package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDiscordSchemeTest {
    @Test
    fun execArgumentQuotesSpacesAndEscapesReservedCharacters() {
        assertEquals("\"/opt/My App/archivetune\"", desktopExecArgument("/opt/My App/archivetune"))
        assertEquals("\"/opt/a\\\\\"b\"", desktopExecArgument("/opt/a\"b"))
        assertEquals("\"/opt/a\\\\\\\\b\"", desktopExecArgument("/opt/a\\b"))
        assertEquals("\"/opt/100%%\"", desktopExecArgument("/opt/100%"))
        val dollar = "\"" + "/opt/" + "\\\\" + "$" + "x" + "\""
        assertEquals(dollar, desktopExecArgument("/opt/\$x"))
    }

    @Test
    fun desktopEntryUsesTheEscapedExecAndDeclaresTheDiscordScheme() {
        val entry = discordDesktopEntry("/opt/My App/archivetune", "1234")

        assertTrue(entry.contains("Exec=\"/opt/My App/archivetune\" %u\n"))
        assertTrue(entry.contains("MimeType=x-scheme-handler/discord-1234;\n"))
    }
}
