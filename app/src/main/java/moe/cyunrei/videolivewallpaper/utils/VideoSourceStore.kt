package moe.cyunrei.videolivewallpaper.utils

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Stores the configured video source.
 *
 * Picked videos are copied into app-private storage. Content URIs returned by
 * the document picker are only readable while the activity that received them
 * is alive, and direct paths into shared storage are not reliably readable
 * under scoped storage. Remote URLs are stored as-is.
 *
 * Every import gets a unique cached file name so the wallpaper service also
 * reloads when the new video replaces the old one.
 */
object VideoSourceStore {
    const val VIDEO_PATH_FILE = "video_live_wallpaper_file_path"

    private const val TAG = "VideoSourceStore"
    private const val CACHED_VIDEO_PREFIX = "wallpaper_video_"

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Copies the video behind [uri] into app-private storage and saves its
     * path. The callback always runs on the main thread.
     */
    fun saveFromUri(context: Context, uri: Uri, callback: (Throwable?) -> Unit) {
        val appContext = context.applicationContext
        ioExecutor.execute {
            val error = try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open video: $uri")
                val cachedFile = input.use { copyToPrivateStorage(appContext, it) }

                val previous = readSource(appContext)
                writeSource(appContext, cachedFile.absolutePath)
                deletePreviousCachedFile(appContext, previous, cachedFile)
                null
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to import selected video", t)
                t
            }
            postResult(callback, error)
        }
    }

    /**
     * Saves a manually entered source. Remote URLs are kept as-is; file paths
     * and content URIs are copied into app-private storage.
     */
    fun saveFromPath(context: Context, rawSource: String, callback: (Throwable?) -> Unit) {
        val source = rawSource.trim()
        if (source.isEmpty()) {
            postResult(callback, IllegalArgumentException("Video path is empty"))
            return
        }

        val appContext = context.applicationContext
        ioExecutor.execute {
            val error = try {
                if (isRemoteSource(source)) {
                    val previous = readSource(appContext)
                    writeSource(appContext, source)
                    deletePreviousCachedFile(appContext, previous, null)
                } else {
                    val parsed = Uri.parse(source)
                    val cachedFile = when (parsed.scheme?.lowercase(Locale.ROOT)) {
                        "content" -> {
                            val input = appContext.contentResolver.openInputStream(parsed)
                                ?: throw IOException("Could not open video: $parsed")
                            input.use { copyToPrivateStorage(appContext, it) }
                        }

                        "file" -> {
                            val path = parsed.path ?: throw IOException("Invalid file URI")
                            copyFileToPrivateStorage(appContext, File(path))
                        }

                        null -> copyFileToPrivateStorage(appContext, File(source))
                        else -> throw IOException("Unsupported video source: $source")
                    }

                    val previous = readSource(appContext)
                    writeSource(appContext, cachedFile.absolutePath)
                    deletePreviousCachedFile(appContext, previous, cachedFile)
                }
                null
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to import video path: $source", t)
                t
            }
            postResult(callback, error)
        }
    }

    fun readSource(context: Context): String? {
        return try {
            context.openFileInput(VIDEO_PATH_FILE)
                .bufferedReader()
                .use { it.readText().trim() }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read video path file", e)
            null
        }
    }

    fun isRemoteSource(source: String): Boolean {
        return when (Uri.parse(source).scheme?.lowercase(Locale.ROOT)) {
            "http", "https", "rtsp" -> true
            else -> false
        }
    }

    private fun copyFileToPrivateStorage(context: Context, source: File): File {
        if (!source.isFile || !source.canRead()) {
            throw IOException("Video file is missing or unreadable: ${source.absolutePath}")
        }
        return FileInputStream(source).use { input ->
            copyToPrivateStorage(context, input)
        }
    }

    private fun copyToPrivateStorage(context: Context, input: InputStream): File {
        val target = File(
            context.noBackupFilesDir,
            CACHED_VIDEO_PREFIX + System.currentTimeMillis()
        )
        try {
            FileOutputStream(target).use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
            return target
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    private fun deletePreviousCachedFile(
        context: Context,
        previous: String?,
        replacement: File?
    ) {
        if (previous == null) return

        val file = File(previous)
        if (replacement != null && file.absolutePath == replacement.absolutePath) return
        if (file.parentFile?.absolutePath != context.noBackupFilesDir.absolutePath) return
        if (!file.name.startsWith(CACHED_VIDEO_PREFIX)) return

        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete previous cached video: $previous")
        }
    }

    private fun writeSource(context: Context, source: String) {
        context.openFileOutput(VIDEO_PATH_FILE, Context.MODE_PRIVATE).use {
            it.write(source.toByteArray())
        }
    }

    private fun postResult(callback: (Throwable?) -> Unit, error: Throwable?) {
        mainHandler.post { callback(error) }
    }
}
