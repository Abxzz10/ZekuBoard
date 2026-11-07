package com.zeku.keyboard

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * Menyesuaikan warna ikon status bar dan navigation bar dengan tema aktif
 * (ikon gelap di tema terang, ikon terang di tema gelap).
 */
fun AppCompatActivity.applySystemBars() {
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = !night
    controller.isAppearanceLightNavigationBars = !night
}
