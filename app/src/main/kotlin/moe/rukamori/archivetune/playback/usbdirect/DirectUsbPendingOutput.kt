package moe.rukamori.archivetune.playback.usbdirect

internal interface DirectUsbPendingOutput {
    fun hasPendingData(): Boolean
}
