package com.zeku.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * Penyimpanan pengaturan terpusat (SharedPreferences).
 *
 * Semua nilai punya default, dicek rentangnya saat dibaca maupun ditulis, dan
 * nilai rusak/tidak dikenal otomatis kembali ke default (tidak membuat crash).
 * Rentang dan default dipakai bersama oleh halaman Preferences Typing dan
 * keyboard, jadi tidak ada angka ganda di dua tempat.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ Pembaca aman

    private fun readBool(key: String, def: Boolean): Boolean =
        try {
            sp.getBoolean(key, def)
        } catch (e: ClassCastException) {
            def
        }

    private fun readInt(key: String, def: Int, min: Int, max: Int): Int {
        val raw = try {
            sp.getInt(key, def)
        } catch (e: ClassCastException) {
            def
        }
        return raw.coerceIn(min, max)
    }

    private fun readString(key: String, def: String): String =
        try {
            sp.getString(key, def) ?: def
        } catch (e: ClassCastException) {
            def
        }

    private fun writeBool(key: String, value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
    }

    private fun writeInt(key: String, value: Int, min: Int, max: Int) {
        sp.edit().putInt(key, value.coerceIn(min, max)).apply()
    }

    // -------------------------------------------------------------------- Tema

    var theme: String
        get() {
            val value = readString(KEY_THEME, THEME_SYSTEM)
            return if (value == THEME_DARK || value == THEME_LIGHT) value else THEME_SYSTEM
        }
        set(value) {
            val safe = if (value == THEME_DARK || value == THEME_LIGHT) value else THEME_SYSTEM
            sp.edit().putString(KEY_THEME, safe).apply()
        }

    /** Mode gelap/terang AndroidX sesuai pilihan tema. */
    fun nightMode(): Int = when (theme) {
        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    // ------------------------------------------------------------ Tombol (Keypad)

    /** Baris angka 1-0 di atas huruf (hanya di layar huruf). */
    var numberRow: Boolean
        get() = readBool(KEY_NUMBER_ROW, true)
        set(value) = writeBool(KEY_NUMBER_ROW, value)

    /** Tekan lama angka di baris angka memasukkan simbolnya (1 menjadi !). */
    var numberRowSymbols: Boolean
        get() = readBool(KEY_NUMBER_ROW_SYMBOLS, true)
        set(value) = writeBool(KEY_NUMBER_ROW_SYMBOLS, value)

    var commaKey: Boolean
        get() = readBool(KEY_COMMA, true)
        set(value) = writeBool(KEY_COMMA, value)

    var periodKey: Boolean
        get() = readBool(KEY_PERIOD, true)
        set(value) = writeBool(KEY_PERIOD, value)

    /** Tekan lama huruf Q sampai P memasukkan angka. */
    var longPressNumbers: Boolean
        get() = readBool(KEY_LP_NUMBERS, true)
        set(value) = writeBool(KEY_LP_NUMBERS, value)

    /** Tekan lama huruf lain memasukkan simbol (A menjadi @, dan seterusnya). */
    var longPressSymbols: Boolean
        get() = readBool(KEY_LP_SYMBOLS, true)
        set(value) = writeBool(KEY_LP_SYMBOLS, value)

    // ----------------------------------------------------------------- Tampilan

    var fontPercent: Int
        get() = readInt(KEY_FONT, FONT_DEFAULT, FONT_MIN, FONT_MAX)
        set(value) = writeInt(KEY_FONT, value, FONT_MIN, FONT_MAX)

    var heightPercent: Int
        get() = readInt(KEY_HEIGHT, HEIGHT_DEFAULT, HEIGHT_MIN, HEIGHT_MAX)
        set(value) = writeInt(KEY_HEIGHT, value, HEIGHT_MIN, HEIGHT_MAX)

    var widthPercent: Int
        get() = readInt(KEY_WIDTH, WIDTH_DEFAULT, WIDTH_MIN, WIDTH_MAX)
        set(value) = writeInt(KEY_WIDTH, value, WIDTH_MIN, WIDTH_MAX)

    /** Mode satu tangan: [ONE_HAND_OFF], [ONE_HAND_LEFT], atau [ONE_HAND_RIGHT]. */
    var oneHandMode: String
        get() {
            val value = readString(KEY_ONE_HAND, ONE_HAND_OFF)
            return if (value == ONE_HAND_LEFT || value == ONE_HAND_RIGHT) value else ONE_HAND_OFF
        }
        set(value) {
            val safe = if (value == ONE_HAND_LEFT || value == ONE_HAND_RIGHT) value else ONE_HAND_OFF
            sp.edit().putString(KEY_ONE_HAND, safe).apply()
        }

    // ------------------------------------------------------------------ Pintasan

    /** Huruf besar otomatis sesuai permintaan kolom teks (awal kalimat, dan seterusnya). */
    var autoCap: Boolean
        get() = readBool(KEY_AUTO_CAP, true)
        set(value) = writeBool(KEY_AUTO_CAP, value)

    /** Huruf besar setelah akhir kalimat, juga di kolom yang tidak memintanya. Butuh [autoCap]. */
    var sentenceCap: Boolean
        get() = readBool(KEY_SENTENCE_CAP, false)
        set(value) = writeBool(KEY_SENTENCE_CAP, value)

    var doubleSpacePeriod: Boolean
        get() = readBool(KEY_DOUBLE_SPACE, false)
        set(value) = writeBool(KEY_DOUBLE_SPACE, value)

    var autoSpacePunct: Boolean
        get() = readBool(KEY_AUTO_SPACE, false)
        set(value) = writeBool(KEY_AUTO_SPACE, value)

    /** Gestur geser di tombol spasi untuk memindah kursor. */
    var gestureSpace: Boolean
        get() = readBool(KEY_GESTURE_SPACE, true)
        set(value) = writeBool(KEY_GESTURE_SPACE, value)

    /** Gestur Shift + hapus untuk memblok lalu menghapus teks. */
    var gestureSelect: Boolean
        get() = readBool(KEY_GESTURE_SELECT, true)
        set(value) = writeBool(KEY_GESTURE_SELECT, value)

    // ---------------------------------------------------------- Pengetuk tombol

    var charPreview: Boolean
        get() = readBool(KEY_CHAR_PREVIEW, true)
        set(value) = writeBool(KEY_CHAR_PREVIEW, value)

    var keyPopup: Boolean
        get() = readBool(KEY_KEY_POPUP, true)
        set(value) = writeBool(KEY_KEY_POPUP, value)

    var vibrate: Boolean
        get() = readBool(KEY_VIBRATE, true)
        set(value) = writeBool(KEY_VIBRATE, value)

    var vibrateStrength: Int
        get() = readInt(KEY_VIBRATE_STRENGTH, STRENGTH_DEFAULT, STRENGTH_MIN, STRENGTH_MAX)
        set(value) = writeInt(KEY_VIBRATE_STRENGTH, value, STRENGTH_MIN, STRENGTH_MAX)

    var sound: Boolean
        get() = readBool(KEY_SOUND, false)
        set(value) = writeBool(KEY_SOUND, value)

    var soundVolume: Int
        get() = readInt(KEY_SOUND_VOLUME, VOLUME_DEFAULT, VOLUME_MIN, VOLUME_MAX)
        set(value) = writeInt(KEY_SOUND_VOLUME, value, VOLUME_MIN, VOLUME_MAX)

    /** Durasi tekan lama (ms): tekan lama huruf dan awal hapus berulang. */
    var longPressDelay: Int
        get() = readInt(KEY_LP_DELAY, LP_DELAY_DEFAULT, LP_DELAY_MIN, LP_DELAY_MAX)
        set(value) = writeInt(KEY_LP_DELAY, value, LP_DELAY_MIN, LP_DELAY_MAX)

    // --------------------------------------------------------- Pulihkan / pantau

    /** Kembalikan semua pengaturan mengetik ke default. Tema tidak ikut diubah. */
    fun restoreTypingDefaults() {
        val editor = sp.edit()
        for (key in TYPING_KEYS) editor.remove(key)
        editor.apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        const val ONE_HAND_OFF = "off"
        const val ONE_HAND_LEFT = "left"
        const val ONE_HAND_RIGHT = "right"

        // Rentang slider (persen kecuali disebut lain). Nilai harus kelipatan langkahnya.
        const val FONT_MIN = 80
        const val FONT_MAX = 130
        const val FONT_DEFAULT = 100
        const val FONT_STEP = 5

        const val HEIGHT_MIN = 80
        const val HEIGHT_MAX = 120
        const val HEIGHT_DEFAULT = 100
        const val HEIGHT_STEP = 5

        const val WIDTH_MIN = 70
        const val WIDTH_MAX = 100
        const val WIDTH_DEFAULT = 100
        const val WIDTH_STEP = 5

        const val STRENGTH_MIN = 10
        const val STRENGTH_MAX = 100
        const val STRENGTH_DEFAULT = 100
        const val STRENGTH_STEP = 10

        const val VOLUME_MIN = 10
        const val VOLUME_MAX = 100
        const val VOLUME_DEFAULT = 100
        const val VOLUME_STEP = 10

        const val LP_DELAY_MIN = 100
        const val LP_DELAY_MAX = 1000
        const val LP_DELAY_DEFAULT = 350
        const val LP_DELAY_STEP = 50

        private const val FILE_NAME = "zekuboard_prefs"
        private const val KEY_THEME = "theme"

        // Kunci lama dipertahankan supaya pengaturan pengguna tidak hilang saat pembaruan.
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_SOUND = "sound"
        private const val KEY_HEIGHT = "height_percent"
        private const val KEY_GESTURE_SPACE = "gesture_space"
        private const val KEY_GESTURE_SELECT = "gesture_select"

        private const val KEY_NUMBER_ROW = "number_row"
        private const val KEY_NUMBER_ROW_SYMBOLS = "number_row_symbols"
        private const val KEY_COMMA = "comma_key"
        private const val KEY_PERIOD = "period_key"
        private const val KEY_LP_NUMBERS = "long_press_numbers"
        private const val KEY_LP_SYMBOLS = "long_press_symbols"
        private const val KEY_FONT = "font_percent"
        private const val KEY_WIDTH = "width_percent"
        /** Publik: halaman Preferences Typing memantau kunci ini (tombol satu tangan di keyboard). */
        const val KEY_ONE_HAND = "one_hand_mode"
        private const val KEY_AUTO_CAP = "auto_cap"
        private const val KEY_SENTENCE_CAP = "sentence_cap"
        private const val KEY_DOUBLE_SPACE = "double_space_period"
        private const val KEY_AUTO_SPACE = "auto_space_punct"
        private const val KEY_CHAR_PREVIEW = "char_preview"
        private const val KEY_KEY_POPUP = "key_popup"
        private const val KEY_VIBRATE_STRENGTH = "vibrate_strength"
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_LP_DELAY = "long_press_delay"

        /** Semua kunci pengaturan mengetik (semua kecuali tema). */
        private val TYPING_KEYS = listOf(
            KEY_VIBRATE, KEY_SOUND, KEY_HEIGHT, KEY_GESTURE_SPACE, KEY_GESTURE_SELECT,
            KEY_NUMBER_ROW, KEY_NUMBER_ROW_SYMBOLS, KEY_COMMA, KEY_PERIOD,
            KEY_LP_NUMBERS, KEY_LP_SYMBOLS, KEY_FONT, KEY_WIDTH, KEY_ONE_HAND,
            KEY_AUTO_CAP, KEY_SENTENCE_CAP, KEY_DOUBLE_SPACE, KEY_AUTO_SPACE,
            KEY_CHAR_PREVIEW, KEY_KEY_POPUP, KEY_VIBRATE_STRENGTH, KEY_SOUND_VOLUME,
            KEY_LP_DELAY
        )
    }
}
