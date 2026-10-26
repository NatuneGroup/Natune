package moe.rukamori.archivetune.desktop.integration

/**
 * JNI boundary for the Linux file chooser in desktopApp/native/browser/browser_linux.cpp. The chooser is created on the
 * same GTK owner loop as the cookie-only sign-in windows, so GTK keeps exactly one owner in the process. Java holds only
 * a ticket: it waits on the answer in bounded slices and cancels without blocking. The export names and these
 * signatures change together with browser_linux.cpp.
 */
internal object NativeGtkFilePicker {
    /** Returns a ticket, or 0 when the GTK owner has no display. Never waits for the user. */
    @JvmStatic
    external fun start(folder: Boolean, multiple: Boolean, title: String, acceptLabel: String, cancelLabel: String): Long

    /**
     * Null while the chooser is still open after [timeoutMillis]. An empty array is a cancelled or empty selection, and a
     * failed chooser throws so that it is never reported as a cancel.
     */
    @JvmStatic
    external fun await(ticket: Long, timeoutMillis: Long): Array<String>?

    /** Stops waiting on [ticket] and asks the owner to close its dialog. Returns at once. */
    @JvmStatic
    external fun cancel(ticket: Long)
}
