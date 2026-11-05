package moe.rukamori.archivetune.shared.ui

import moe.rukamori.archivetune.shared.data.platform.ios.IosCrashCapture

/**
 * The Kotlin side capture of a reported, non-fatal Objective-C exception. It must never be called from the process's
 * uncaught-exception handler: a dying process may not initialise or allocate the Kotlin runtime, and the file and
 * Keychain work behind it can hang before the record is written.
 */
fun iosCaptureNSException(name: String, reason: String?, callStack: List<String>) {
    IosCrashCapture.captureNSException(name, reason, callStack)
}

/**
 * The path the app shell's fatal exception handler writes to, prepared at launch so the handler itself only ever uses
 * POSIX calls on an already-open descriptor. The record is classified into a normal crash report on the next launch.
 */
fun iosNativeCrashRecordPath(): String? = IosCrashCapture.nativeRecordPath()
