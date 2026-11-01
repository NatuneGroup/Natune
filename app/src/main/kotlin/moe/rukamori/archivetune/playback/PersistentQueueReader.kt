package moe.rukamori.archivetune.playback

import android.content.Context
import java.io.ObjectInputStream
import moe.rukamori.archivetune.models.PersistQueue
import moe.rukamori.archivetune.playback.MusicService.Companion.PERSISTENT_QUEUE_FILE

/**
 * Best-effort read of the serialized legacy queue. A missing or corrupt file restores nothing —
 * the caller treats null the same way, so no failure is worth logging here.
 */
internal fun readPersistentQueue(context: Context): PersistQueue? {
    val file = context.filesDir.resolve(PERSISTENT_QUEUE_FILE)
    if (!file.exists() || !file.isFile) return null
    return try {
        file.inputStream().use { fis ->
            ObjectInputStream(fis).use { input ->
                input.readObject() as? PersistQueue
            }
        }
    } catch (e: Exception) {
        null
    }
}
