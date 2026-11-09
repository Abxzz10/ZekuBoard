package com.zeku.keyboard

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/**
 * Halaman Preferences Typing: satu halaman panjang berkategori (gaya Gboard).
 * Bagian 1: fitur yang sudah ada dipindah ke sini (tinggi keyboard, gestur, getar, suara).
 * Setiap baris membaca dan menulis langsung ke [Prefs]; keyboard memakainya saat kolom
 * teks dibuka lagi.
 */
class PreferencesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_preferences)
        applySystemBars()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        container = findViewById(R.id.prefsContainer)
        buildPage()
    }

    private fun buildPage() {
        // Kalimat pengantar di atas.
        val intro = addSection(R.string.sub_preferences)
        (intro.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = 0
            bottomMargin = dp(2)
        }

        // Tombol
        addSection(R.string.sec_keys)
        val keys = newCard()
        addSwitch(
            keys, R.string.pref_number_row_title, R.string.pref_number_row_desc,
            { prefs.numberRow }, { prefs.numberRow = it }
        )
        addSwitch(
            keys, R.string.pref_comma_title, R.string.pref_comma_desc,
            { prefs.commaKey }, { prefs.commaKey = it }
        )
        addSwitch(
            keys, R.string.pref_period_title, R.string.pref_period_desc,
            { prefs.periodKey }, { prefs.periodKey = it }
        )

        // Tekan lama
        addSection(R.string.sec_long_press)
        val longPress = newCard()
        addSwitch(
            longPress, R.string.pref_lp_numbers_title, R.string.pref_lp_numbers_desc,
            { prefs.longPressNumbers }, { prefs.longPressNumbers = it }
        )
        addSwitch(
            longPress, R.string.pref_lp_symbols_title, R.string.pref_lp_symbols_desc,
            { prefs.longPressSymbols }, { prefs.longPressSymbols = it }
        )
        addSlider(
            longPress, R.string.pref_lp_delay_title, R.string.pref_lp_delay_desc,
            Prefs.LP_DELAY_MIN, Prefs.LP_DELAY_MAX, Prefs.LP_DELAY_STEP,
            { prefs.longPressDelay }, { prefs.longPressDelay = it },
            R.string.ms_fmt
        )

        // Tampilan
        addSection(R.string.sec_display)
        val display = newCard()
        addSlider(
            display, R.string.height_title, R.string.pref_height_desc,
            Prefs.HEIGHT_MIN, Prefs.HEIGHT_MAX, Prefs.HEIGHT_STEP,
            { prefs.heightPercent }, { prefs.heightPercent = it }
        )

        // Pintasan (gestur)
        addSection(R.string.sec_shortcuts)
        val shortcuts = newCard()
        addSwitch(
            shortcuts, R.string.gesture_space_title, R.string.gesture_space_desc,
            { prefs.gestureSpace }, { prefs.gestureSpace = it }
        )
        addSwitch(
            shortcuts, R.string.gesture_select_title, R.string.gesture_select_desc,
            { prefs.gestureSelect }, { prefs.gestureSelect = it }
        )

        // Pengetuk tombol
        addSection(R.string.sec_feedback)
        val feedback = newCard()
        addSwitch(
            feedback, R.string.vibrate_title, R.string.vibrate_desc,
            { prefs.vibrate }, { prefs.vibrate = it }
        )
        addSwitch(
            feedback, R.string.sound_title, R.string.sound_desc,
            { prefs.sound }, { prefs.sound = it }
        )
    }

    // ------------------------------------------------------------ Pembuat baris

    private fun addSection(@StringRes title: Int): TextView {
        val view = layoutInflater.inflate(R.layout.item_pref_section, container, false) as TextView
        view.setText(title)
        container.addView(view)
        return view
    }

    private fun newCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.bg_card)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        container.addView(card, lp)
        return card
    }

    private fun addDivider(card: LinearLayout) {
        val line = View(this)
        line.setBackgroundColor(ContextCompat.getColor(this, R.color.line))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            maxOf(1, dp(1))
        )
        lp.marginStart = dp(18)
        lp.marginEnd = dp(18)
        card.addView(line, lp)
    }

    private fun addSwitch(
        card: LinearLayout,
        @StringRes title: Int,
        @StringRes desc: Int,
        get: () -> Boolean,
        set: (Boolean) -> Unit
    ) {
        val row = layoutInflater.inflate(R.layout.item_pref_switch, card, false)
        val sw = row.findViewById<MaterialSwitch>(R.id.prefSwitch)
        row.findViewById<TextView>(R.id.prefTitle).setText(title)
        row.findViewById<TextView>(R.id.prefDesc).setText(desc)
        sw.contentDescription = getString(title)
        // Nilai awal diisi sebelum pendengar dipasang, supaya tidak dihitung sebagai perubahan.
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
        // Ketuk di mana saja pada baris untuk menyalakan/mematikan.
        row.setOnClickListener { sw.toggle() }

        if (card.childCount > 0) addDivider(card)
        card.addView(row)
    }

    private fun addSlider(
        card: LinearLayout,
        @StringRes title: Int,
        @StringRes desc: Int,
        min: Int,
        max: Int,
        step: Int,
        get: () -> Int,
        set: (Int) -> Unit,
        @StringRes fmt: Int = R.string.height_fmt
    ) {
        val row = layoutInflater.inflate(R.layout.item_pref_slider, card, false)
        val slider = row.findViewById<Slider>(R.id.prefSlider)
        val valueText = row.findViewById<TextView>(R.id.prefValue)
        row.findViewById<TextView>(R.id.prefTitle).setText(title)
        row.findViewById<TextView>(R.id.prefDesc).setText(desc)
        slider.contentDescription = getString(title)

        slider.valueFrom = min.toFloat()
        slider.valueTo = max.toFloat()
        slider.stepSize = step.toFloat()
        val start = snap(get(), min, max, step)
        slider.value = start.toFloat()
        valueText.text = getString(fmt, start)

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val v = value.toInt()
                set(v)
                valueText.text = getString(fmt, v)
            }
        }

        if (card.childCount > 0) addDivider(card)
        card.addView(row)
    }

    // ---------------------------------------------------------------- Bantuan

    /** Samakan nilai ke kelipatan langkah slider; slider menolak nilai di luar langkahnya. */
    private fun snap(value: Int, min: Int, max: Int, step: Int): Int {
        val clamped = value.coerceIn(min, max)
        val steps = (clamped - min + step / 2) / step
        return (min + steps * step).coerceIn(min, max)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
