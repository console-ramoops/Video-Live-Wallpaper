package moe.cyunrei.videolivewallpaper.service

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import moe.cyunrei.videolivewallpaper.utils.VideoSourceStore
import moe.cyunrei.videolivewallpaper.utils.WallpaperSettings
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoLiveWallpaperService : WallpaperService() {

    internal inner class VideoEngine : Engine() {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

        private val audioManager by lazy {
            getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        private val powerManager by lazy {
            getSystemService(Context.POWER_SERVICE) as PowerManager
        }

        private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        private var audioFocusRequest: AudioFocusRequest? = null
        private var hasAudioFocus = false

        private var mediaPlayer: MediaPlayer? = null
        private var broadcastReceiver: BroadcastReceiver? = null
        private var currentVideoPath: String? = null

        private var isPrepared = false
        private var visibleNow = false
        private var sawVisibilityChange = false
        private var isCompleted = false
        private var isBatterySaverOn = false
        private var needsPlayOnceReset = false

        private var retryCount = 0
        private var loadGeneration = 0

        private val retryRunnable = Runnable {
            if (surfaceHolder.surface?.isValid == true) {
                initPlayer()
            }
        }

        private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    hasAudioFocus = false
                    updatePlayerVolume()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasAudioFocus = true
                    updatePlayerVolume()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            isBatterySaverOn = powerManager.isPowerSaveMode

            val intentFilter = IntentFilter().apply {
                addAction(ACTION_SETTINGS_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }

            broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_SETTINGS_CHANGED -> applySettings()
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                            isBatterySaverOn = powerManager.isPowerSaveMode
                            if (isBatterySaverOn) {
                                pausePlayback()
                            } else if (visibleNow) {
                                startPlayback()
                            }
                        }
                    }
                }
            }.also { receiver ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(receiver, intentFilter)
                }
            }
        }

        /**
         * Reads the configured video source and validates it on a background
         * thread to avoid StrictMode disk-read violations.
         */
        private fun readValidVideoPathAsync(onResult: (String?) -> Unit) {
            val generation = ++loadGeneration
            ioExecutor.execute {
                val path = VideoSourceStore.readSource(this@VideoLiveWallpaperService)

                val validPath = path?.takeIf { candidate ->
                    isSourceReadable(candidate).also { valid ->
                        if (!valid) {
                            Log.w(TAG, "Video source missing or unreadable: $candidate")
                        }
                    }
                }

                mainHandler.post {
                    if (generation == loadGeneration) {
                        onResult(validPath)
                    }
                }
            }
        }

        /**
         * Content URIs and remote streams are left for MediaPlayer to open.
         * Local files must exist and be readable by this process.
         */
        private fun isSourceReadable(source: String): Boolean {
            return when (Uri.parse(source).scheme?.lowercase(Locale.ROOT)) {
                "http", "https", "rtsp", "rtmp" -> true
                "content" -> true
                "file" -> {
                    val path = Uri.parse(source).path
                    path != null && File(path).let { it.isFile && it.canRead() }
                }

                null -> File(source).let { it.isFile && it.canRead() }
                else -> false
            }
        }

        /** Chooses the right MediaPlayer data source for the saved source. */
        private fun setPlayerDataSource(player: MediaPlayer, source: String) {
            when (Uri.parse(source).scheme?.lowercase(Locale.ROOT)) {
                "content" -> player.setDataSource(this@VideoLiveWallpaperService, Uri.parse(source))
                "file" -> {
                    val path = Uri.parse(source).path ?: throw IOException("Invalid file URI")
                    player.setDataSource(path)
                }

                else -> player.setDataSource(source)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            retryCount = 0
            initPlayer()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            val player = mediaPlayer
            val surface = holder.surface
            if (player != null && surface != null && surface.isValid) {
                try {
                    player.setSurface(surface)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Failed to update surface on size change", e)
                }
            }
        }

        private fun initPlayer() {
            mainHandler.removeCallbacks(retryRunnable)
            releasePlayer()

            readValidVideoPathAsync { validPath ->
                if (validPath == null) {
                    currentVideoPath = null
                    return@readValidVideoPathAsync
                }

                val surface = surfaceHolder.surface
                if (surface == null || !surface.isValid) return@readValidVideoPathAsync

                currentVideoPath = validPath
                val player = MediaPlayer()
                mediaPlayer = player

                try {
                    player.setAudioAttributes(audioAttributes)
                    // Order matters: setDataSource must precede setVideoScalingMode and prepareAsync
                    setPlayerDataSource(player, validPath)
                    player.setSurface(surface)
                    player.setVideoScalingMode(
                        MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    )
                    applyPlayerSettings(player)

                    player.setOnPreparedListener { prepared ->
                        if (prepared === mediaPlayer) {
                            isPrepared = true
                            retryCount = 0
                            applyPlayerSettings(prepared)
                            if ((visibleNow || !sawVisibilityChange) && !isBatterySaverOn) {
                                startPlayback()
                            }
                        }
                    }

                    player.setOnCompletionListener { completed ->
                        if (completed === mediaPlayer) {
                            isCompleted = true
                            abandonAudioFocus()
                        }
                    }

                    player.setOnErrorListener { failed, what, extra ->
                        if (failed === mediaPlayer) {
                            Log.e(TAG, "MediaPlayer error (what=$what, extra=$extra)")
                            releasePlayer()
                            if (retryCount < MAX_RETRIES) {
                                retryCount++
                                Log.w(TAG, "Retrying player initialization ($retryCount/$MAX_RETRIES)")
                                mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
                            }
                        }
                        true
                    }

                    player.prepareAsync()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize MediaPlayer for path: $validPath", e)
                    releasePlayer()
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val wasVisible = visibleNow
            visibleNow = visible
            sawVisibilityChange = true

            if (visible) {
                if (!wasVisible || needsPlayOnceReset) {
                    resetForPlayOnce()
                    needsPlayOnceReset = false
                }
                if (!isBatterySaverOn) {
                    startPlayback()
                }
            } else {
                needsPlayOnceReset = true
                pausePlayback()
            }
        }

        /**
         * In play-once mode, rewind to the first frame whenever the wallpaper
         * becomes visible again.
         */
        private fun resetForPlayOnce() {
            val player = mediaPlayer ?: return
            if (!isPrepared) return
            if (WallpaperSettings.isLoopingEnabled(this@VideoLiveWallpaperService)) return

            isCompleted = false
            try {
                player.seekTo(0)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to seek to start in play-once mode", e)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            mainHandler.removeCallbacks(retryRunnable)
            releasePlayer()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            mainHandler.removeCallbacksAndMessages(null)
            ioExecutor.shutdownNow()
            releasePlayer()
            broadcastReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Receiver was already unregistered", e)
                }
            }
            broadcastReceiver = null
            super.onDestroy()
        }

        private fun applySettings() {
            readValidVideoPathAsync { newPath ->
                val player = mediaPlayer
                // A player reload may have released the current instance while
                // this read was in flight; restart it instead of doing nothing.
                if (newPath != currentVideoPath || player == null) {
                    retryCount = 0
                    initPlayer()
                    return@readValidVideoPathAsync
                }

                if (!isPrepared) return@readValidVideoPathAsync

                applyPlayerSettings(player)
                if (visibleNow && !isBatterySaverOn) {
                    startPlayback()
                }
            }
        }

        private fun applyPlayerSettings(player: MediaPlayer) {
            val looping = WallpaperSettings.isLoopingEnabled(this@VideoLiveWallpaperService)
            try {
                player.isLooping = looping
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to set looping state", e)
            }
            updatePlayerVolume(player)
        }

        private fun updatePlayerVolume(player: MediaPlayer? = mediaPlayer) {
            val activePlayer = player ?: return
            val soundEnabled = WallpaperSettings.isSoundEnabled(this@VideoLiveWallpaperService)

            val shouldUnmute = if (soundEnabled && visibleNow) {
                hasAudioFocus || requestAudioFocus()
            } else {
                if (!soundEnabled) abandonAudioFocus()
                false
            }

            val volume = if (shouldUnmute) 1.0f else 0.0f
            try {
                activePlayer.setVolume(volume, volume)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to update player volume", e)
            }
        }

        private fun requestAudioFocus(): Boolean {
            if (hasAudioFocus) return true

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                    .build()
                    .also { audioFocusRequest = it }
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            return hasAudioFocus
        }

        private fun abandonAudioFocus() {
            if (!hasAudioFocus) return
            hasAudioFocus = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        }

        private fun startPlayback() {
            val player = mediaPlayer ?: return
            if (!isPrepared) return

            if (isCompleted) {
                if (!player.isLooping) return
                isCompleted = false
                try {
                    player.seekTo(0)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Failed to seek to 0 on restart", e)
                    return
                }
            }

            updatePlayerVolume(player)

            try {
                if (!player.isPlaying) {
                    player.start()
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to start MediaPlayer", e)
            }
        }

        private fun pausePlayback() {
            abandonAudioFocus()
            val player = mediaPlayer ?: return
            if (!isPrepared) return
            try {
                if (player.isPlaying) {
                    player.pause()
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to pause MediaPlayer", e)
            }
        }

        private fun releasePlayer() {
            abandonAudioFocus()
            isPrepared = false
            isCompleted = false
            val player = mediaPlayer
            mediaPlayer = null
            if (player != null) {
                try {
                    player.setOnPreparedListener(null)
                    player.setOnCompletionListener(null)
                    player.setOnErrorListener(null)
                    player.setSurface(null)
                    player.reset()
                    player.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Exception while releasing MediaPlayer", e)
                }
            }
        }
    }

    override fun onCreateEngine(): Engine {
        return VideoEngine()
    }

    companion object {
        private const val TAG = "VideoLiveWallpaperSvc"
        private const val ACTION_SETTINGS_CHANGED =
            "moe.cyunrei.videolivewallpaper.SETTINGS_CHANGED"

        private const val MAX_RETRIES = 1
        private const val RETRY_DELAY_MS = 500L

        fun notifySettingsChanged(context: Context) {
            Intent(ACTION_SETTINGS_CHANGED).apply {
                setPackage(context.packageName)
            }.also { context.sendBroadcast(it) }
        }

        fun setToWallPaper(context: Context) {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, VideoLiveWallpaperService::class.java)
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct live wallpaper picker failed; falling back to chooser", e)
                try {
                    context.startActivity(
                        Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to launch live wallpaper chooser", ex)
                }
            }
        }
    }
}
