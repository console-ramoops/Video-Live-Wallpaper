@file:Suppress("DEPRECATION")

package moe.cyunrei.videolivewallpaper.activity

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.PreferenceActivity
import android.preference.SwitchPreference
import moe.cyunrei.videolivewallpaper.R
import moe.cyunrei.videolivewallpaper.service.VideoLiveWallpaperService
import moe.cyunrei.videolivewallpaper.utils.WallpaperSettings

class SettingsActivity : PreferenceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.settings)

        val soundPreference =
            findPreference(resources.getString(R.string.preference_play_video_with_sound)) as SwitchPreference
        soundPreference.isChecked = WallpaperSettings.isSoundEnabled(this)
        soundPreference.onPreferenceChangeListener =
            OnPreferenceChangeListener { _, newValue ->
                WallpaperSettings.setSoundEnabled(this, newValue as Boolean)
                VideoLiveWallpaperService.notifySettingsChanged(this)
                true
            }

        val loopPreference =
            findPreference(resources.getString(R.string.preference_loop_video)) as SwitchPreference
        loopPreference.isChecked = WallpaperSettings.isLoopingEnabled(this)
        loopPreference.onPreferenceChangeListener =
            OnPreferenceChangeListener { _, newValue ->
                WallpaperSettings.setLoopingEnabled(this, newValue as Boolean)
                VideoLiveWallpaperService.notifySettingsChanged(this)
                true
            }

        val hideIconPreference =
            findPreference(resources.getString(R.string.preference_hide_icon_from_launcher)) as SwitchPreference
        val packageManager = packageManager
        val componentName = ComponentName(this, MainActivity::class.java)
        val iconHidden = packageManager.getComponentEnabledSetting(componentName) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        hideIconPreference.isChecked = iconHidden
        hideIconPreference.onPreferenceChangeListener =
            OnPreferenceChangeListener { _, newValue ->
                packageManager.setComponentEnabledSetting(
                    componentName,
                    if (newValue as Boolean) {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    },
                    PackageManager.DONT_KILL_APP
                )
                true
            }
    }
}
