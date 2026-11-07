package com.zeku.keyboard

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Penyimpanan pengaturan sederhana (SharedPreferences).
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var theme: String
        get() = sp.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) {
            sp.edit().putString(KEY_THEME, value).apply()
        }

    var vibrate: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, true)
        set(value) {
            sp.edit().putBoolean(KEY_VIBRATE, value).apply()
        }

    var sound: Boolean
        get() = sp.getBoolean(KEY_SOUND, false)
        set(value) {
            sp.edit().putBoolean(KEY_SOUND, value).apply()
        }

    var heightPercent: Int
        get() = sp.getInt(KEY_HEIGHT, 100)
        set(value) {
            sp.edit().putInt(KEY_HEIGHT, value).apply()
        }

    /** Mode gelap/terang AndroidX sesuai pilihan tema. */
    fun nightMode(): Int = when (theme) {
        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        private const val FILE_NAME = "zekuboard_prefs"
        private const val KEY_THEME = "theme"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_SOUND = "sound"
        private const val KEY_HEIGHT = "height_percent"
    }
}
