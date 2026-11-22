package com.zeku.keyboard

import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
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

    // Dua saklar yang saling meniadakan (baris angka dan tekan lama untuk angka).
    private var swNumberRow: MaterialSwitch? = null
    private var swLpNumbers: MaterialSwitch? = null

    // Baris slider yang ikut mati (abu-abu dan tidak bisa digeser) bila saklarnya dimatikan.
    private var rowVibrate: View? = null
    private var rowVolume: View? = null
    private var rowClipDays: View? = null
    private var rowCursorRepeat: View? = null
    private var swClipShots: MaterialSwitch? = null

    private val vibrator: Vibrator? by lazy { createVibrator() }

    // Activity yang sama dipakai untuk tiga halaman: Preferences Typing, Tools Keyboard, dan Paket Unduhan.
    private var toolsPage = false
    private var downloadsPage = false

    // Status tiap bahasa di Paket Unduhan: 0 belum diunduh, 1 mengunduh, 2 terunduh, 3 gagal.
    private val dlState = HashMap<String, Int>()
    private val dlRows = HashMap<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        toolsPage = intent.getStringExtra(EXTRA_PAGE) == PAGE_TOOLS
        downloadsPage = intent.getStringExtra(EXTRA_PAGE) == PAGE_DOWNLOADS
        setContentView(R.layout.activity_preferences)
        applySystemBars()

        findViewById<TextView>(R.id.pageTitle)
            .setText(
                when {
                    toolsPage -> R.string.title_tools
                    downloadsPage -> R.string.title_downloads
                    else -> R.string.title_preferences
                }
            )
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        container = findViewById(R.id.prefsContainer)
        when {
            toolsPage -> buildToolsPage()
            downloadsPage -> buildDownloadsPage()
            else -> buildPage()
        }
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
        swNumberRow = addSwitch(
            keys, R.string.pref_number_row_title, R.string.pref_number_row_desc,
            { prefs.numberRow },
            {
                prefs.numberRow = it
                // Baris angka menyala: tekan lama untuk angka otomatis mati.
                if (it) swLpNumbers?.isChecked = false
            }
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
        swLpNumbers = addSwitch(
            longPress, R.string.pref_lp_numbers_title, R.string.pref_lp_numbers_desc,
            { prefs.longPressNumbers },
            {
                prefs.longPressNumbers = it
                // Tekan lama untuk angka menyala: baris angka otomatis mati.
                if (it) swNumberRow?.isChecked = false
            }
        )
        addSwitch(
            longPress, R.string.pref_emoji_title, R.string.pref_emoji_desc,
            { prefs.emoji }, { prefs.emoji = it }
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

        // Bilah atas: tempat Tools keyboard dan kolom saran kata (isinya menyusul)
        addSection(R.string.sec_top_bars)
        val topBars = newCard()
        addSwitch(
            topBars, R.string.pref_tools_title, R.string.pref_tools_desc,
            { prefs.toolsBar }, { prefs.toolsBar = it }
        )
        addSwitch(
            topBars, R.string.pref_suggest_title, R.string.pref_suggest_desc,
            { prefs.suggestionBar }, { prefs.suggestionBar = it }
        )

        // Saran kata: belajar dari ketikan (datanya hanya di perangkat)
        addSection(R.string.sec_suggestions)
        val suggestions = newCard()
        addSwitch(
            suggestions, R.string.pref_learn_title, R.string.pref_learn_desc,
            { prefs.learnTyping }, { prefs.learnTyping = it }
        )
        addAction(
            suggestions, R.string.pref_learn_clear_title, R.string.pref_learn_clear_desc
        ) { confirmClearLearned() }

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
            { prefs.vibrate },
            {
                prefs.vibrate = it
                rowVibrate?.let { row -> setRowEnabled(row, it) }
            }
        )
        val vibrateRow = addSlider(
            feedback, R.string.pref_vibrate_strength_title, R.string.pref_vibrate_strength_desc,
            Prefs.STRENGTH_MIN, Prefs.STRENGTH_MAX, Prefs.STRENGTH_STEP,
            { prefs.vibrateStrength }, { prefs.vibrateStrength = it },
            R.string.ms_fmt,
            onRelease = { previewVibration(prefs.vibrateStrength) }
        )
        rowVibrate = vibrateRow
        setRowEnabled(vibrateRow, prefs.vibrate)

        addSwitch(
            feedback, R.string.sound_title, R.string.sound_desc,
            { prefs.sound },
            {
                prefs.sound = it
                rowVolume?.let { row -> setRowEnabled(row, it) }
            }
        )
        val volumeRow = addSlider(
            feedback, R.string.pref_volume_title, R.string.pref_volume_desc,
            Prefs.VOLUME_MIN, Prefs.VOLUME_MAX, Prefs.VOLUME_STEP,
            { prefs.soundVolume }, { prefs.soundVolume = it },
            onRelease = { previewSound(prefs.soundVolume) }
        )
        rowVolume = volumeRow
        setRowEnabled(volumeRow, prefs.sound)
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
    ): MaterialSwitch {
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
        return sw
    }

    /** Baris yang bisa diketuk tanpa saklar. */
    private fun addAction(
        card: LinearLayout,
        @StringRes title: Int,
        @StringRes desc: Int,
        onClick: () -> Unit
    ) {
        val row = layoutInflater.inflate(R.layout.item_pref_switch, card, false)
        row.findViewById<View>(R.id.prefSwitch).visibility = View.GONE
        row.findViewById<TextView>(R.id.prefTitle).setText(title)
        row.findViewById<TextView>(R.id.prefDesc).setText(desc)
        row.setOnClickListener { onClick() }

        if (card.childCount > 0) addDivider(card)
        card.addView(row)
    }

    private fun confirmClearLearned() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clear_learned_title)
            .setMessage(R.string.dialog_clear_learned_message)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                LearnedWords.clear(this)
                Toast.makeText(this, R.string.toast_learned_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** Halaman Tools Keyboard: pengaturan alat di bilah atas keyboard (sekarang papan klip). */
    private fun buildToolsPage() {
        val intro = addSection(R.string.sub_tools)
        (intro.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = 0
            bottomMargin = dp(2)
        }

        // Kontrol kursor: panel panah, pilih, salin, tempel, potong
        addSection(R.string.sec_cursor)
        val cursor = newCard()
        addSwitch(
            cursor, R.string.pref_cursor_title, R.string.pref_cursor_desc,
            { prefs.cursorTool },
            {
                prefs.cursorTool = it
                rowCursorRepeat?.let { row -> setRowEnabled(row, it) }
            }
        )
        val repeatRow = addSlider(
            cursor, R.string.pref_cursor_repeat_title, R.string.pref_cursor_repeat_desc,
            Prefs.CURSOR_REPEAT_MIN, Prefs.CURSOR_REPEAT_MAX, Prefs.CURSOR_REPEAT_STEP,
            { prefs.cursorRepeatMs }, { prefs.cursorRepeatMs = it },
            R.string.ms_fmt
        )
        rowCursorRepeat = repeatRow
        setRowEnabled(repeatRow, prefs.cursorTool)

        // Papan klip: riwayat teks yang disalin (datanya hanya di perangkat)
        addSection(R.string.sec_clipboard)
        val clipboard = newCard()
        addSwitch(
            clipboard, R.string.pref_clip_title, R.string.pref_clip_desc,
            { prefs.clipboardHistory }, { prefs.clipboardHistory = it }
        )
        swClipShots = addSwitch(
            clipboard, R.string.pref_shot_title, R.string.pref_shot_desc,
            { prefs.clipScreenshots && ScreenshotStore.hasPermission(this) },
            { on ->
                if (!on) {
                    prefs.clipScreenshots = false
                } else if (ScreenshotStore.hasPermission(this)) {
                    prefs.clipScreenshots = true
                } else {
                    // Belum ada izin akses foto: saklar kembali mati, lalu minta izinnya.
                    swClipShots?.isChecked = false
                    startActivity(Intent(this, ScreenshotAccessActivity::class.java))
                }
            }
        )
        addSwitch(
            clipboard, R.string.pref_clip_expire_title, R.string.pref_clip_expire_desc,
            { prefs.clipExpire },
            {
                prefs.clipExpire = it
                rowClipDays?.let { row -> setRowEnabled(row, it) }
            }
        )
        val daysRow = addSlider(
            clipboard, R.string.pref_clip_days_title, R.string.pref_clip_days_desc,
            0, Prefs.CLIP_DAYS.size - 1, 1,
            { Prefs.CLIP_DAYS.indexOf(prefs.clipExpireDays).coerceAtLeast(0) },
            { prefs.clipExpireDays = Prefs.CLIP_DAYS[it] },
            label = { getString(R.string.clip_days_fmt, Prefs.CLIP_DAYS[it]) }
        )
        rowClipDays = daysRow
        setRowEnabled(daysRow, prefs.clipExpire)
        addAction(
            clipboard, R.string.pref_clip_clear_title, R.string.pref_clip_clear_desc
        ) { confirmClearClipboard() }
    }

    /** Halaman Paket Unduhan: data bahasa terjemahan, diunduh dan dihapus manual. */
    private fun buildDownloadsPage() {
        val intro = addSection(R.string.sub_downloads)
        (intro.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = 0
            bottomMargin = dp(2)
        }

        addSection(R.string.sec_translation)
        val settings = newCard()
        addSwitch(
            settings, R.string.pref_dl_wifi_title, R.string.pref_dl_wifi_desc,
            { prefs.translateWifiOnly }, { prefs.translateWifiOnly = it }
        )

        addSection(R.string.sec_languages)
        val card = newCard()
        for (code in TranslationHelper.languages()) {
            if (card.childCount > 0) addDivider(card)
            val row = layoutInflater.inflate(R.layout.item_download_row, card, false)
            row.findViewById<TextView>(R.id.dlName).text = TranslationHelper.languageName(code)
            row.findViewById<View>(R.id.dlButton).setOnClickListener { onDownloadButton(code) }
            dlRows[code] = row
            dlState[code] = if (code == TranslationHelper.ENGLISH) 2 else 0
            card.addView(row)
            refreshDownloadRow(code)
        }

        addSection(R.string.dl_attribution)

        // Status asli dari perangkat: bahasa mana yang sudah terunduh.
        TranslationHelper.downloadedModels { have ->
            for (code in dlRows.keys) {
                if (dlState[code] != 1) dlState[code] = if (code in have) 2 else 0
                refreshDownloadRow(code)
            }
        }
    }

    private fun onDownloadButton(code: String) {
        when (dlState[code]) {
            1 -> return
            2 -> {
                if (code == TranslationHelper.ENGLISH) return
                TranslationHelper.delete(code) { ok ->
                    if (ok) dlState[code] = 0
                    refreshDownloadRow(code)
                }
            }
            else -> {
                dlState[code] = 1
                refreshDownloadRow(code)
                TranslationHelper.download(code, prefs.translateWifiOnly) { ok ->
                    dlState[code] = if (ok) 2 else 3
                    refreshDownloadRow(code)
                }
            }
        }
    }

    private fun refreshDownloadRow(code: String) {
        val row = dlRows[code] ?: return
        val status = row.findViewById<TextView>(R.id.dlStatus)
        val button = row.findViewById<TextView>(R.id.dlButton)
        val builtIn = code == TranslationHelper.ENGLISH
        button.visibility = if (builtIn) View.GONE else View.VISIBLE
        button.isEnabled = true
        button.alpha = 1f
        when (dlState[code]) {
            1 -> {
                status.setText(
                    if (prefs.translateWifiOnly) R.string.dl_status_downloading_wifi else R.string.dl_status_downloading
                )
                button.setText(R.string.dl_button_wait)
                button.isEnabled = false
                button.alpha = 0.5f
            }
            2 -> {
                status.setText(if (builtIn) R.string.dl_status_builtin else R.string.dl_status_done)
                button.setText(R.string.btn_delete)
            }
            3 -> {
                status.setText(R.string.dl_status_failed)
                button.setText(R.string.dl_button_retry)
            }
            else -> {
                status.setText(R.string.dl_status_none)
                button.setText(R.string.dl_button_download)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Izin foto bisa diberi atau dicabut di luar layar ini: samakan saklar screenshot
        // (saklarnya hanya ada di halaman Tools Keyboard).
        swClipShots?.isChecked = prefs.clipScreenshots && ScreenshotStore.hasPermission(this)
    }

    private fun confirmClearClipboard() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clip_clear_title)
            .setMessage(R.string.dialog_clip_clear_message)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                ClipboardStore.clear(this)
                Toast.makeText(this, R.string.toast_clip_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
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
        @StringRes fmt: Int = R.string.height_fmt,
        onRelease: (() -> Unit)? = null,
        label: ((Int) -> String)? = null
    ): View {
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
        valueText.text = label?.invoke(start) ?: getString(fmt, start)

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val v = value.toInt()
                set(v)
                valueText.text = label?.invoke(v) ?: getString(fmt, v)
            }
        }
        if (onRelease != null) {
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    onRelease()
                }
            })
        }

        if (card.childCount > 0) addDivider(card)
        card.addView(row)
        return row
    }

    /** Matikan atau nyalakan satu baris slider: mati berarti abu-abu dan tidak bisa digeser. */
    private fun setRowEnabled(row: View, enabled: Boolean) {
        row.findViewById<Slider>(R.id.prefSlider).isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.38f
    }

    /** Getar contoh sepanjang [ms] milidetik, dipakai saat slider dilepas. */
    private fun previewVibration(ms: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Bunyi contoh pada volume [percent] persen, dipakai saat slider dilepas. */
    private fun previewSound(percent: Int) {
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        am.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, (percent / 100f).coerceIn(0f, 1f))
    }

    @Suppress("DEPRECATION")
    private fun createVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }

    // ---------------------------------------------------------------- Bantuan

    /** Samakan nilai ke kelipatan langkah slider; slider menolak nilai di luar langkahnya. */
    private fun snap(value: Int, min: Int, max: Int, step: Int): Int {
        val clamped = value.coerceIn(min, max)
        val steps = (clamped - min + step / 2) / step
        return (min + steps * step).coerceIn(min, max)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /** Isi: [PAGE_TOOLS] untuk halaman Tools Keyboard; kosong untuk Preferences Typing. */
        const val EXTRA_PAGE = "page"
        const val PAGE_TOOLS = "tools"

        /** Isi: [PAGE_DOWNLOADS] untuk halaman Paket Unduhan. */
        const val PAGE_DOWNLOADS = "downloads"
    }
}
