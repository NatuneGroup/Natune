/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DeepLinkSingleInstanceTest {
    @Test fun secondaryForwardsWithoutCreatingOwnerAndPrimaryCanRestart() {
        val directory = Files.createTempDirectory("archivetune-instance-test")
        val received = LinkedBlockingQueue<String>()
        try {
            DesktopSingleInstance.acquire(directory, "archivetune://open/first") { received += it ?: "restore" }!!.use {
                assertEquals("archivetune://open/first", received.poll(2, TimeUnit.SECONDS))
                assertNull(DesktopSingleInstance.acquire(directory, "archivetune://open/second") { error("second owner") })
                assertEquals("archivetune://open/second", received.poll(2, TimeUnit.SECONDS))
                assertNull(DesktopSingleInstance.acquire(directory, null) { error("second owner") })
                assertEquals("restore", received.poll(2, TimeUnit.SECONDS))
                assertFalse(Files.readString(directory.resolve("instance.endpoint")).contains("archivetune://"))
            }
            assertNotNull(DesktopSingleInstance.acquire(directory, null) {}).close()
        } finally {
            Files.deleteIfExists(directory.resolve("instance.endpoint"))
            Files.deleteIfExists(directory.resolve("instance.lock"))
            Files.delete(directory)
        }
    }
}
