package moe.cyunrei.videolivewallpaper.service

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import moe.cyunrei.videolivewallpaper.utils.WallpaperSettings
import java.io.IOException

class VideoLiveWallpaperService : WallpaperService() {
    internal inner class VideoEngine : Engine() {
        private var mediaPlayer: MediaPlayer? = null
        private var broadcastReceiver: BroadcastReceiver? = null
        private var isPrepared = false
        private var isVisible = true
        private var isCompleted = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val intentFilter = IntentFilter(ACTION_SETTINGS_CHANGED)
            broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    applySettings()
                }
            }.also { registerReceiver(it, intentFilter) }
        }

        private fun readVideoPath(): String? {
            return try {
                this@VideoLiveWallpaperService
                    .openFileInput(VIDEO_FILE_NAME)
                    .bufferedReader()
                    .use { it.readText().trim() }
                    .takeIf { it.isNotEmpty() }
            } catch (e: IOException) {
                null
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            releasePlayer()

            val path = readVideoPath() ?: return
            val player = MediaPlayer()
            mediaPlayer = player
            try {
                player.setSurface(holder.surface)
                player.setDataSource(path)
                applyPlayerSettings(player)
                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                player.setOnPreparedListener { prepared ->
                    if (prepared === mediaPlayer) {
                        isPrepared = true
                        applyPlayerSettings(prepared)
                        if (isVisible) startPlayback()
                    }
                }
                player.setOnCompletionListener { completed ->
                    if (completed === mediaPlayer) isCompleted = true
                }
                player.setOnErrorListener { failed, _, _ ->
                    if (failed === mediaPlayer) releasePlayer()
                    true
                }
                player.prepareAsync()
            } catch (e: Exception) {
                e.printStackTrace()
                releasePlayer()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                startPlayback()
            } else {
                pausePlayback()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            releasePlayer()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            releasePlayer()
            broadcastReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (e: IllegalArgumentException) {
                    e.printStackTrace()
                }
            }
            broadcastReceiver = null
            super.onDestroy()
        }

        /**
         * Re-reads the file based settings. Called when the settings screen
         * changes something, so the running wallpaper updates immediately.
         */
        private fun applySettings() {
            val player = mediaPlayer ?: return
            // While preparing, the listener applies the latest values instead.
            if (!isPrepared) return
            applyPlayerSettings(player)
            if (isVisible) startPlayback()
        }

        private fun applyPlayerSettings(player: MediaPlayer) {
            val looping = WallpaperSettings.isLoopingEnabled(this@VideoLiveWallpaperService)
            try {
                player.isLooping = looping
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
            val volume = if (WallpaperSettings.isSoundEnabled(this@VideoLiveWallpaperService)) {
                1.0f
            } else {
                0.0f
            }
            try {
                player.setVolume(volume, volume)
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }

        private fun startPlayback() {
            val player = mediaPlayer ?: return
            if (!isPrepared) return

            if (isCompleted) {
                // With looping disabled the last frame stays on screen. If the
                // user turns looping back on, replay the video from the start.
                if (!player.isLooping) return
                isCompleted = false
                try {
                    player.seekTo(0)
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    return
                }
            }

            try {
                if (!player.isPlaying) player.start()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }

        private fun pausePlayback() {
            try {
                if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }

        private fun releasePlayer() {
            isPrepared = false
            isCompleted = false
            val player = mediaPlayer
            mediaPlayer = null
            try {
                player?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreateEngine(): Engine {
        return VideoEngine()
    }

    companion object {
        private const val VIDEO_FILE_NAME = "video_live_wallpaper_file_path"
        private const val ACTION_SETTINGS_CHANGED =
            "moe.cyunrei.videolivewallpaper.SETTINGS_CHANGED"

        /**
         * Notifies every running wallpaper engine that a playback setting
         * changed. The values themselves are read from [WallpaperSettings], so
         * another app cannot change the wallpaper by sending this broadcast.
         */
        fun notifySettingsChanged(context: Context) {
            Intent(ACTION_SETTINGS_CHANGED).apply {
                setPackage(context.packageName)
            }.also { context.sendBroadcast(it) }
        }

        fun setToWallPaper(context: Context) {
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, VideoLiveWallpaperService::class.java)
                )
            }.also {
                context.startActivity(it)
            }
            try {
                WallpaperManager.getInstance(context).clear()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
