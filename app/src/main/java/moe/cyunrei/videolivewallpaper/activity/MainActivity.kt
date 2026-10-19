package moe.cyunrei.videolivewallpaper.activity

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import moe.cyunrei.videolivewallpaper.R
import moe.cyunrei.videolivewallpaper.service.VideoLiveWallpaperService
import moe.cyunrei.videolivewallpaper.utils.DocumentUtils.getPath


class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        permissionCheck
        findViewById<Button>(R.id.choose_video_file).setOnClickListener { chooseVideo() }

        findViewById<Button>(R.id.add_video_file_path).setOnClickListener {
            val edit = EditText(this@MainActivity)
            AlertDialog.Builder(
                this@MainActivity,
                R.style.Theme_VideoLiveWallpaper_Dialog
            ).apply {
                setTitle(getString(R.string.add_path))
                setView(edit)
                setPositiveButton(
                    getString(R.string.apply)
                ) { _, _ ->
                    val videoFilePath: String = edit.text.toString().trim()
                    this@MainActivity.openFileOutput(
                        VIDEO_PATH_FILE,
                        Context.MODE_PRIVATE
                    ).use {
                        it.write(videoFilePath.toByteArray())
                    }
                    VideoLiveWallpaperService.setToWallPaper(this@MainActivity)
                }
                setNegativeButton(
                    getString(R.string.cancel)
                ) { _, _ -> }
                setCancelable(true)
                create().apply {
                    setCanceledOnTouchOutside(true)
                    show()
                }
            }
        }

        findViewById<Button>(R.id.settings).setOnClickListener {
            Intent(this@MainActivity, SettingsActivity::class.java).also {
                startActivity(it)
            }
        }
    }

    private val permissionCheck: Unit
        get() {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ), 1
            )
        }

    private fun chooseVideo() {
        Intent().apply {
            type = "video/*"
            action = Intent.ACTION_GET_CONTENT
        }.also { startActivityForResult(it, REQUEST_VIDEO) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return

        val uri: Uri = data?.data ?: return
        val videoPath: String = getPath(this, uri) ?: return
        this.openFileOutput(
            VIDEO_PATH_FILE,
            Context.MODE_PRIVATE
        ).use {
            it.write(videoPath.toByteArray())
        }
        VideoLiveWallpaperService.setToWallPaper(this)
    }

    companion object {
        private const val VIDEO_PATH_FILE = "video_live_wallpaper_file_path"
        private const val REQUEST_VIDEO = 1
    }
}
