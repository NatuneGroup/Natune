package moe.rukamori.archivetune.desktop.integration

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one-line reason a host service reports. Both `NoClassDefFoundError` lines on the real PC named only the error
 * class, which hid the only useful fact in the report: *which* class was missing and why.
 */
class HostUnavailableReasonTest {
    @Test
    fun aLinkageErrorNamesTheMissingClassThroughItsCause() {
        val error = NoClassDefFoundError("com/sun/security/auth/module/UnixSystem")
            .apply { initCause(ClassNotFoundException("com.sun.security.auth.module.UnixSystem")) }
        assertEquals(
            "NoClassDefFoundError: com/sun/security/auth/module/UnixSystem <- " +
                "ClassNotFoundException: com.sun.security.auth.module.UnixSystem",
            unavailableReason(error),
        )
    }

    @Test
    fun aMessageLessErrorKeepsItsClassName() {
        assertEquals("IllegalStateException", unavailableReason(IllegalStateException()))
    }

    @Test
    fun aMultiLineMessageStaysOnOneLine() {
        assertEquals(
            "IllegalStateException: no session bus was found on this host",
            unavailableReason(IllegalStateException("no session bus\n  was found on this host")),
        )
    }

    @Test
    fun theChainIsBoundedAndCannotLoop() {
        val root = RuntimeException("root")
        var error: Throwable = root
        repeat(6) { index -> error = RuntimeException("layer $index", error) }
        assertEquals(
            "RuntimeException: layer 5 <- RuntimeException: layer 4 <- " +
                "RuntimeException: layer 3 <- RuntimeException: layer 2",
            unavailableReason(error),
        )
        // A throwable that names itself as its own cause must not spin.
        assertEquals("SelfCaused: self", unavailableReason(SelfCaused()))
    }

    /** `initCause(this)` is refused by the JDK, so the only way to describe a self-caused throwable is to override. */
    private class SelfCaused : RuntimeException("self") {
        override val cause: Throwable get() = this
    }
}
