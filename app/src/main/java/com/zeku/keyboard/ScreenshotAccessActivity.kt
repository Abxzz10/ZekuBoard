package com.zeku.keyboard

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat

/**
 * Layar tanpa tampilan untuk meminta izin akses foto, karena keyboard tidak bisa meminta izin sendiri.
 * Bila izin diberikan, fitur screenshot di papan klip langsung dinyalakan.
 */
class ScreenshotAccessActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ScreenshotStore.hasPermission(this)) {
            enableAndFinish()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(ScreenshotStore.permission()), REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableAndFinish()
        } else {
            // Ditolak (mungkin dengan "jangan tanya lagi"): arahkan ke pengaturan aplikasi.
            Toast.makeText(this, R.string.shot_permission_denied, Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                // Tidak ada layar pengaturan: cukup tutup.
            }
            finish()
        }
    }

    private fun enableAndFinish() {
        Prefs(this).clipScreenshots = true
        finish()
    }

    private companion object {
        const val REQUEST = 4101
    }
}
