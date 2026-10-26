package moe.rukamori.archivetune.shared.ui

import moe.rukamori.archivetune.shared.data.platform.ios.IosCrashCapture

/**
 * Called by the Swift app's Objective-C uncaught-exception handler, which Kotlin cannot install itself because the
 * handler must be a C function pointer. It writes the report next to the log and returns; the process then ends.
 */
fun iosCaptureNSException(name: String, reason: String?, callStack: List<String>) {
    IosCrashCapture.captureNSException(name, reason, callStack)
}
