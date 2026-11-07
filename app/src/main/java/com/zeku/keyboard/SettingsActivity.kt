package com.zeku.keyboard

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBars()

        val prefs = Prefs(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val swVibrate = findViewById<MaterialSwitch>(R.id.swVibrate)
        swVibrate.isChecked = prefs.vibrate
        swVibrate.setOnCheckedChangeListener { _, checked -> prefs.vibrate = checked }

        val swSound = findViewById<MaterialSwitch>(R.id.swSound)
        swSound.isChecked = prefs.sound
        swSound.setOnCheckedChangeListener { _, checked -> prefs.sound = checked }

        val heightValue = findViewById<TextView>(R.id.heightValue)
        val slider = findViewById<Slider>(R.id.sliderHeight)
        val startHeight = prefs.heightPercent.coerceIn(80, 120)
        slider.value = startHeight.toFloat()
        heightValue.text = getString(R.string.height_fmt, startHeight)
        slider.addOnChangeListener { _, value, _ ->
            val percent = value.toInt()
            prefs.heightPercent = percent
            heightValue.text = getString(R.string.height_fmt, percent)
        }

        val version = (try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }) ?: "1.0.0"
        findViewById<TextView>(R.id.aboutVersion).text = getString(R.string.version_fmt, version)
    }
}
