package com.zeku.keyboard

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Halaman Pengaturan: kini hanya informasi aplikasi. Pengaturan mengetik
 * (getar, suara, tinggi, gestur) pindah ke halaman Preferences Typing.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBars()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val version = (try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }) ?: "1.0.0"
        findViewById<TextView>(R.id.aboutVersion).text = getString(R.string.version_fmt, version)
    }
}
