package moe.cyunrei.videolivewallpaper.activity

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import moe.cyunrei.videolivewallpaper.R
import moe.cyunrei.videolivewallpaper.service.VideoLiveWallpaperService
import moe.cyunrei.videolivewallpaper.utils.VideoSourceStore


class MainActivity : Activity() {

    private lateinit var chooseVideoButton: Button
    private lateinit var addPathButton: Button
    private var importGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        chooseVideoButton = findViewById(R.id.choose_video_file)
        addPathButton = findViewById(R.id.add_video_file_path)

        chooseVideoButton.setOnClickListener { chooseVideo() }

        addPathButton.setOnClickListener {
            if (hasStoragePermission()) {
                showAddPathDialog()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(requiredStoragePermission()),
                    REQUEST_STORAGE_PERMISSION
                )
            }
        }

        findViewById<Button>(R.id.settings).setOnClickListener {
            Intent(this@MainActivity, SettingsActivity::class.java).also {
                startActivity(it)
            }
        }
    }

    /**
     * Only needed for the manual path option. The document picker grants
     * access to the returned URI, and picked videos are copied immediately.
     */
    private fun requiredStoragePermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            requiredStoragePermission()
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE_PERMISSION) return
        if (isFinishing || isDestroyed) return

        // Show the dialog even when denied: app-private paths and content
        // URIs can still be imported.
        showAddPathDialog()
    }

    private fun showAddPathDialog() {
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
                importPath(edit.text.toString())
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

    private fun chooseVideo() {
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }.also { startActivityForResult(it, REQUEST_VIDEO) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return

        val uri: Uri = data?.data ?: run {
            Toast.makeText(this, R.string.video_not_selected, Toast.LENGTH_SHORT).show()
            return
        }
        importUri(uri)
    }

    private fun importUri(uri: Uri) {
        val generation = beginImport()
        VideoSourceStore.saveFromUri(this, uri) { error ->
            finishImport(generation, error)
        }
    }

    private fun importPath(source: String) {
        if (source.isBlank()) {
            Toast.makeText(this, R.string.video_not_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val generation = beginImport()
        VideoSourceStore.saveFromPath(this, source) { error ->
            finishImport(generation, error)
        }
    }

    private fun beginImport(): Int {
        importGeneration++
        chooseVideoButton.isEnabled = false
        addPathButton.isEnabled = false
        Toast.makeText(this, R.string.video_preparing, Toast.LENGTH_SHORT).show()
        return importGeneration
    }

    private fun finishImport(generation: Int, error: Throwable?) {
        if (generation != importGeneration) return

        setActionsEnabled(true)

        if (error != null) {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, R.string.video_load_failed, Toast.LENGTH_LONG).show()
            }
            return
        }

        // Reload the running wallpaper even if this activity is being recreated.
        VideoLiveWallpaperService.notifySettingsChanged(this)

        if (isFinishing || isDestroyed) return
        VideoLiveWallpaperService.setToWallPaper(this)
    }

    private fun setActionsEnabled(enabled: Boolean) {
        chooseVideoButton.isEnabled = enabled
        addPathButton.isEnabled = enabled
    }

    companion object {
        private const val REQUEST_VIDEO = 1
        private const val REQUEST_STORAGE_PERMISSION = 2
    }
}
