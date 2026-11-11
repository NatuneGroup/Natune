package moe.rukamori.archivetune.playback.usbdirect

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

internal class UsbDirectDevices(private val context: Context, private val onChanged: () -> Unit) {
    val manager = context.getSystemService(UsbManager::class.java)
    private val permissionAction = "${context.packageName}.USB_AUDIO_PERMISSION"
    private val requested = mutableSetOf<Int>()
    private val refused = mutableSetOf<Int>()
    private val descriptors = mutableMapOf<Int, ByteArray>()
    private var registered = false
    private val lease = AtomicBoolean(false)
    @Volatile private var active: UsbDirectAudioOutput? = null
    @Volatile private var musicVolume = readVolumeFraction()
    private fun volumeChanged() { musicVolume = readVolumeFraction(); active?.refreshVolume() }
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { volumeChanged() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION" || intent.action == "android.media.STREAM_MUTE_CHANGED_ACTION") {
                if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) volumeChanged()
                return
            }
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                synchronized(this@UsbDirectDevices) {
                    requested.remove(device.deviceId)
                    refused.remove(device.deviceId)
                    descriptors.remove(device.deviceId)
                }
                active?.takeIf { it.deviceId == device.deviceId }?.markDetached()
            }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) requestPermission()
            onChanged()
        }
    }

    @Synchronized fun watch() {
        if (registered) return
        ContextCompat.registerReceiver(context, receiver, IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        registered = true
        requestPermission()
    }

    fun release() {
        synchronized(this) {
            if (registered) {
                context.unregisterReceiver(receiver)
                context.contentResolver.unregisterContentObserver(volumeObserver)
                registered = false
            }
            descriptors.clear()
        }
        active?.release()
    }

    fun audioDevices(): List<UsbDevice> = runCatching { manager?.deviceList?.values.orEmpty().toList() }
        .getOrDefault(emptyList()).filter { device ->
        (0 until device.interfaceCount).any { index ->
            val iface = device.getInterface(index)
            iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2 &&
                (0 until iface.endpointCount).any { endpoint ->
                    val ep = iface.getEndpoint(endpoint)
                    ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                }
        }
    }.sortedBy { it.deviceId }

    @Synchronized fun requestPermission() {
        val usb = manager ?: return
        for (device in audioDevices()) {
            if (usb.hasPermission(device) || !requested.add(device.deviceId)) continue
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, device.deviceId,
                Intent(permissionAction).setPackage(context.packageName), flags)
            runCatching { usb.requestPermission(device, pending) }
                .onFailure { Timber.w(it, "USB DAC permission request failed") }
        }
    }

    @Synchronized fun raw(device: UsbDevice): ByteArray? {
        val usb = manager ?: return null
        if (!usb.hasPermission(device) || device.deviceId in refused) return null
        descriptors[device.deviceId]?.let { return it }
        val connection = runCatching { usb.openDevice(device) }.getOrNull() ?: return null
        return try { connection.rawDescriptors?.also { descriptors[device.deviceId] = it } }
        finally { connection.close() }
    }

    fun volumeFraction(): Float = musicVolume

    private fun readVolumeFraction(): Float = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return@runCatching 0f
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) return@runCatching 0f
        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }.getOrDefault(0f)

    fun hasActiveOutput(): Boolean = active != null
    fun acquire(): Boolean = lease.compareAndSet(false, true)
    fun opened(output: UsbDirectAudioOutput) { active = output }
    fun closed(output: UsbDirectAudioOutput) {
        if (active === output) active = null
        lease.set(false)
        onChanged()
    }
    fun abandonOpen() { lease.set(false) }
    @Synchronized fun failed(deviceId: Int) { refused.add(deviceId) }
}
