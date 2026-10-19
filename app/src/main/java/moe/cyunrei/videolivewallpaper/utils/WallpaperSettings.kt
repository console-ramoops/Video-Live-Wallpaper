package moe.cyunrei.videolivewallpaper.utils

import android.content.Context
import java.io.File

/**
 * Tiny file based settings store.
 *
 * The wallpaper service runs in its own process (":wallpaper"), and
 * SharedPreferences are not synchronised reliably between processes. Marker
 * files are cheap to read and work no matter which process accesses them.
 */
object WallpaperSettings {
    private const val SOUND_MARKER = "unmute"
    private const val PLAY_ONCE_MARKER = "play_once"

    /** Sound is off by default (same behaviour as previous versions). */
    fun isSoundEnabled(context: Context): Boolean = marker(context, SOUND_MARKER).exists()

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        setMarker(context, SOUND_MARKER, enabled)
    }

    /** Looping is on by default. The marker means "play once per appearance". */
    fun isLoopingEnabled(context: Context): Boolean =
        !marker(context, PLAY_ONCE_MARKER).exists()

    fun setLoopingEnabled(context: Context, looping: Boolean) {
        setMarker(context, PLAY_ONCE_MARKER, !looping)
    }

    private fun marker(context: Context, name: String): File = File(context.filesDir, name)

    private fun setMarker(context: Context, name: String, present: Boolean) {
        val file = marker(context, name)
        try {
            if (present) {
                if (!file.exists()) file.createNewFile()
            } else if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
