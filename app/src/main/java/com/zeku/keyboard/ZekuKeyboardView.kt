package com.zeku.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Intent
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.SparseArray
import android.util.SparseBooleanArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

enum class ShiftState { OFF, ONCE, LOCK }

/** Aksi di panel kontrol kursor. */
enum class EditAction { LEFT, RIGHT, UP, DOWN, HOME, END, SELECT_ALL, CUT, COPY, PASTE }

/** Isi bilah atas bila Tools keyboard dan kolom saran kata sama-sama menyala. */
enum class TopBarMode { TOOLS, SUGGEST }

/** Jenis aksi pada tombol enter, menentukan ikonnya. */
enum class EnterKind { ENTER, SEARCH, SEND, NEXT, DONE }

private class KeyBox(val key: Key, val cell: RectF, val face: RectF)

/** Ukuran panel emoji: baris tab di atas, kisi di tengah, baris tombol bawah. */
private class PanelGeo(
    val tabsTop: Float,
    val tabsBottom: Float,
    val gridTop: Float,
    val gridBottom: Float,
    val bottomTop: Float,
    val bottomBottom: Float,
    val cols: Int,
    val cell: Float
)

private class Palette(
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val shadow: Int,
    val text: Int,
    val popup: Int,
    val popupStroke: Int
) {
    companion object {
        val DARK = Palette(
            background = 0xFF0E0E0E.toInt(),
            key = 0xFF3A3A3A.toInt(),
            keyPressed = 0xFF5E5E5E.toInt(),
            shadow = 0xFF000000.toInt(),
            text = 0xFFF2F2F2.toInt(),
            popup = 0xFF4A4A4A.toInt(),
            popupStroke = 0xFF6A6A6A.toInt()
        )
        val LIGHT = Palette(
            background = 0xFFD9D9D9.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFBEBEBE.toInt(),
            shadow = 0xFF9E9E9E.toInt(),
            text = 0xFF111111.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            popupStroke = 0xFFBDBDBD.toInt()
        )
    }
}

/**
 * Tampilan keyboard yang digambar sendiri (Canvas).
 * Menangani sentuhan (multi-touch), shift, ganti layar huruf/simbol,
 * hapus berulang, pratinjau huruf, getar, suara, serta dua gestur:
 * geser spasi (pindah kursor) dan Shift + hapus (blok lalu hapus).
 */
class ZekuKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKeyText(text: String)
        fun onKeyDelete()
        fun onKeyEnter()

        /** Pindah kursor [steps] karakter (negatif = kiri, positif = kanan). */
        fun onCursorMove(steps: Int)

        /** Mulai seleksi dari posisi kursor. Mengembalikan false bila tidak didukung. */
        fun onSelectBegin(): Boolean

        /** Ubah seleksi: [chars] negatif = ke kiri dari titik awal, positif = ke kanan. */
        fun onSelectChange(chars: Int)

        /** Hapus teks yang sedang terseleksi. */
        fun onSelectCommit()

        /** Batalkan seleksi tanpa menghapus, kursor kembali ke titik awal. */
        fun onSelectCancel()

        /** Pengguna mengetuk satu saran kata: ganti kata yang sedang diketik dengan [word] + spasi. */
        fun onSuggestionPick(word: String)

        /** Pengguna memilih screenshot di papan klip: tempel [uri] sebagai gambar. */
        fun onPasteImage(uri: Uri, mime: String)

        /**
         * Aksi kontrol kursor. [extend] benar bila mode Pilih menyala: gerakan kursor memperluas
         * blok teks (seperti menahan Shift).
         */
        fun onEditAction(action: EditAction, extend: Boolean)

        /** Teks di kolom untuk diterjemahkan: yang diblok, atau seluruh isi kolom bila tidak ada blok. */
        fun onReadFieldText(): String

        /** Sisipkan [text] di kursor, tanpa lewat pengetikan biasa. */
        fun onInsertText(text: String)

        /** Ganti teks yang diblok (atau seluruh isi kolom bila tidak ada blok) dengan [text]. */
        fun onReplaceFieldText(text: String)
    }

    var listener: Listener? = null

    var shiftState: ShiftState = ShiftState.OFF
        private set

    var currentLayout: LayoutMode = LayoutMode.LETTERS
        private set

    var isDarkTheme: Boolean = false
        private set

    val themeBackground: Int
        get() = palette.background

    private val prefs = Prefs(context)
    private var palette: Palette = Palette.LIGHT
    private var enterKind = EnterKind.ENTER
    private var heightScale = 1f
    private var vibrateOn = true
    private var soundOn = false
    private var gestureSpaceOn = true
    private var gestureSelectOn = true
    private var longPressNumbersOn = true
    private var longPressDelay = Prefs.LP_DELAY_DEFAULT.toLong()
    private var vibrateMs = Prefs.STRENGTH_DEFAULT.toLong()
    private var soundGain = 1f
    private var emojiOn = true

    // Pilihan tombol di layar huruf (dari Preferences Typing).
    private var numberRowOn = true
    private var commaOn = true
    private var periodOn = true

    // Bilah atas: satu ruang untuk Tools keyboard dan kolom saran kata (isinya menyusul).
    // Bila keduanya menyala, Tools tampil dulu, mengetik beralih ke saran, spasi dua kali kembali ke Tools.
    private var toolsOn = true
    private var suggestOn = true
    private var barH = 0f
    private var topMode = TopBarMode.TOOLS

    // Selama kolom teks baru dibuka dan belum disentuh, bilah atas tetap Tools.
    private var topBarLocked = true

    // Kolom saran kata: saran terkini dan jari yang sedang menekan salah satu kotaknya.
    private var suggestions: List<String> = emptyList()
    private var barPointer = -1
    private var barDownCell = -1
    private var barCell = -1

    // Panel emoji (dibuka dengan tekan lama tombol koma), menggantikan tombol selama terbuka.
    private var emojiOpen = false
    private var emojiTab = 0
    private var emojiScroll = 0f
    private var emojiLists: List<List<String>>? = null
    private var emojiRecents: List<String> = emptyList()
    private var panelTouchActive = false
    private var panelRegion = PANEL_NONE
    private var panelTarget = -1
    private var panelDownY = 0f
    private var panelLastY = 0f
    private var panelScrolling = false
    private var emojiPressedIndex = -1
    private var panelPressedBottom = -1
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Papan klip (alat pertama di bilah Tools): panel daftar teks yang disalin.
    private var clipOpen = false
    private var clipItems: List<ClipItem> = emptyList()
    private var clipScroll = 0f
    private var clipSelecting = false
    private val clipSelected = HashSet<Long>()
    private var clipDownIndex = -1
    private var clipDownPart = 0
    private var clipHeaderTarget = -1
    private var clipLongFired = false

    // Dua mode papan klip: 0 = salinan teks, 1 = screenshot terbaru.
    private var clipMode = 0
    private var shotItems: List<ShotItem> = emptyList()
    private var shotReady = false
    private var shotLoaded = false
    private var shotScroll = 0f
    private val shotSelected = HashSet<Long>()
    private var shotPromptPressed = false

    // Panel kontrol kursor (alat kedua di bilah Tools).
    private var cursorToolOn = true
    private var cursorRepeatMs = Prefs.CURSOR_REPEAT_DEFAULT.toLong()
    private var editOpen = false
    private var editSelecting = false
    private var hasSelection = false
    private var editDownButton = -1
    private var editPressedButton = -1
    private var editTopTarget = -1

    // Mode Ubah ukuran: tinggi dan posisi naik-turun keyboard, di bawah lapisan gelap.
    private var resizeOpen = false
    private var resizeMode = RESIZE_NONE
    private var resizeButtonDown = -1
    private var resizeStartRawY = 0f
    private var resizeStartPercent = 100f
    private var resizeStartOffset = 0f
    private var kbOffsetDp = 0f

    // Panel Terjemahan (alat keempat di bilah Tools): teks di kolom diterjemahkan lalu diganti atau disisipkan.
    private var trOpen = false
    private var trFrom = "id"
    private var trTo = "en"
    private val trInput = StringBuilder()
    private var trCardH = 0f
    private var trPointer = -1
    private var trTarget = -1
    private var trDownY = 0f
    private var trLastX = 0f
    private var trLastY = 0f
    private var trScrolling = false
    private var trState = TR_IDLE
    private var trResult = ""
    private var trMissingNames = ""
    private var trRequestId = 0

    // Pemilih bahasa di dalam panel Terjemahan: 0 = tertutup, 1 = memilih bahasa asal, 2 = bahasa tujuan.
    private var trPicking = 0
    private var trPickScroll = 0f
    private var trDownloaded: Set<String> = setOf(TranslationHelper.ENGLISH)
    private val editRepeatRunnable = object : Runnable {
        override fun run() {
            val action = editActionFor(editDownButton) ?: return
            if (action == EditAction.LEFT || action == EditAction.RIGHT ||
                action == EditAction.UP || action == EditAction.DOWN
            ) {
                listener?.onEditAction(action, editSelecting)
                repeatHandler.postDelayed(this, cursorRepeatMs)
            }
        }
    }
    private val clipLpRunnable = Runnable { clipLongPress() }

    // Ketukan pada tombol alat di bilah Tools.
    private var toolPointer = -1
    private var toolDownSlot = -1
    private var toolPressed = false

    /** Baris tombol untuk layar yang sedang tampil. */
    private var rows: List<List<Key>> = emptyList()

    private var rowHeight = 0f
    private var topPad = 0f
    private var topExtra = 0f
    private var bottomPad = 0f
    private var sidePad = 0f
    private var hInset = 0f
    private var vInset = 0f
    private var radius = 0f

    private val topTotal: Float
        get() = topPad + topExtra

    private val boxes = ArrayList<KeyBox>()
    private val pointers = SparseArray<KeyBox>()
    private var previewBox: KeyBox? = null
    private var previewPointer = -1
    private var lastShiftTap = 0L

    // Status gestur geser spasi (pindah kursor).
    private var spaceId = -1
    private var spaceStartX = 0f
    private var spaceApplied = 0
    private var spaceActive = false

    // Status gestur Shift + hapus (blok lalu hapus).
    private var selDeleteId = -1
    private var selShiftId = -1
    private var selStartX = 0f
    private var selLast = 0
    private var selActive = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    // Font hanya untuk tulisan di tombol; teks yang diketik ke aplikasi tidak terpengaruh.
    private val keyTypeface: Typeface = loadKeyTypeface(context)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = keyTypeface
    }
    // Simbol kecil di pojok kanan atas tombol (petunjuk tekan lama).
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = keyTypeface
    }
    private val barTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = keyTypeface
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = keyTypeface
    }
    private val clipTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val icons = HashMap<Int, Drawable?>()

    private val repeatHandler = Handler(Looper.getMainLooper())
    // Tekan lama huruf: jari yang sedang ditunggu dan tombolnya.
    private var lpId = -1
    private var lpBox: KeyBox? = null
    private val longFired = SparseBooleanArray()
    private val lpRunnable = Runnable { fireAltPress() }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            listener?.onKeyDelete()
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val vibrator: Vibrator? = createVibrator(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    init {
        applyPrefs()
    }

    // ------------------------------------------------------------ API publik

    /** Baca ulang pengaturan (tema, tinggi, tombol, getar, suara, gestur) dari [Prefs]. */
    fun applyPrefs() {
        vibrateOn = prefs.vibrate
        soundOn = prefs.sound
        gestureSpaceOn = prefs.gestureSpace
        gestureSelectOn = prefs.gestureSelect
        longPressNumbersOn = prefs.longPressNumbers
        longPressDelay = prefs.longPressDelay.toLong()
        vibrateMs = prefs.vibrateStrength.toLong()
        soundGain = (prefs.soundVolume / 100f).coerceIn(0f, 1f)
        emojiOn = prefs.emoji
        if (!emojiOn) emojiOpen = false
        cursorToolOn = prefs.cursorTool
        cursorRepeatMs = prefs.cursorRepeatMs.toLong()
        if (!cursorToolOn) editOpen = false
        numberRowOn = prefs.numberRow
        commaOn = prefs.commaKey
        periodOn = prefs.periodKey
        toolsOn = prefs.toolsBar
        suggestOn = prefs.suggestionBar
        if (!resizeOpen) {
            heightScale = prefs.heightPercent.coerceIn(Prefs.HEIGHT_MIN, Prefs.HEIGHT_MAX) / 100f
            kbOffsetDp = prefs.keyboardOffsetDp.toFloat()
        }
        isDarkTheme = when (prefs.theme) {
            Prefs.THEME_DARK -> true
            Prefs.THEME_LIGHT -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        palette = if (isDarkTheme) Palette.DARK else Palette.LIGHT
        rebuildRows()
        updateMetrics()
        requestLayout()
        buildBoxes()
        invalidate()
    }

    fun showLayout(mode: LayoutMode) {
        if (mode == currentLayout) return
        currentLayout = mode
        rebuildRows()
        updateMetrics()
        // Jumlah baris bisa berbeda antara layar huruf dan simbol (tanpa baris angka).
        requestLayout()
        buildBoxes()
        invalidate()
    }

    fun applyShift(state: ShiftState) {
        if (shiftState == state) return
        shiftState = state
        invalidate()
    }

    fun applyEnterKind(kind: EnterKind) {
        if (enterKind == kind) return
        enterKind = kind
        invalidate()
    }

    /**
     * Perbarui bilah atas dari teks sebelum kursor dan kandidat saran dari mesin prediksi.
     * Bila Tools dan kolom saran sama-sama menyala, aturannya:
     * - Tools: kolom baru dibuka dan belum disentuh, tidak ada teks sebelum kursor (kosong atau
     *   hanya spasi), mesin prediksi tidak punya kandidat, atau teks berakhir dua spasi atau lebih.
     * - Saran: ada teks sebelum kursor dan ada kandidat, termasuk tebakan kata berikutnya setelah
     *   satu kata dan satu spasi.
     * [userDriven] benar bila perubahan datang dari pengguna (mengetik, hapus, kursor berpindah);
     * itu melepas kunci Tools awal.
     */
    fun setTopBarState(textBeforeCursor: String, words: List<String>, userDriven: Boolean) {
        val next = words.take(SUGGESTION_COUNT)
        val changed = next != suggestions
        suggestions = next
        if (userDriven) topBarLocked = false

        val trailingSpaces = textBeforeCursor.takeLastWhile { it == ' ' }.length
        val mode = if (topBarLocked || textBeforeCursor.isBlank() || trailingSpaces >= 2 || next.isEmpty()) {
            TopBarMode.TOOLS
        } else {
            TopBarMode.SUGGEST
        }
        if (mode != topMode) {
            topMode = mode
            invalidate()
        } else if (changed && activeTopBar() == TopBarMode.SUGGEST) {
            invalidate()
        }
    }

    /** Bilah atas kembali ke Tools dan terkunci sampai pengguna menyentuh kolom teks. */
    fun resetTopBar() {
        topBarLocked = true
        if (topMode != TopBarMode.TOOLS) {
            topMode = TopBarMode.TOOLS
            invalidate()
        }
    }

    // ----------------------------------------------------------- Ukuran/tata letak

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun rebuildRows() {
        rows = KeyboardLayouts.get(
            currentLayout,
            LayoutOptions(numberRowOn, commaOn, periodOn)
        )
    }

    private fun updateMetrics() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        rowHeight = dp(if (landscape) 38f else 50f) * heightScale
        topPad = dp(8f)
        bottomPad = dp(6f) + dp(kbOffsetDp)
        sidePad = dp(if (landscape) 16f else 4f)
        hInset = dp(3f)
        vInset = dp(if (landscape) 3f else 4f)
        radius = dp(10f)

        // Tinggi bilah atas: satu bilah saja, berapa pun yang menyala (Tools, saran, atau keduanya
        // bergantian). Keduanya mati: tidak ada ruang. Area ini bukan area tombol: sentuhan di sini
        // diabaikan (lihat findBox dan buildBoxes), jadi tidak ada tombol yang ikut terketik.
        if (trOpen) {
            // Terjemahan: kartu hasil dan kartu ketik di atas bilah bahasa; tombol tetap qwerty di bawahnya.
            trCardH = dp(if (landscape) 36f else 46f)
            barH = dp(44f)
            topExtra = trAreaHeight() + barH
        } else {
            trCardH = 0f
            barH = if (toolsOn || suggestOn) dp(if (landscape) 32f else 40f) else 0f
            topExtra = barH
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateMetrics()
        // Panel emoji butuh paling sedikit 5 baris tinggi supaya kisinya cukup lega.
        val rowCount = if (emojiOpen || clipOpen || editOpen) max(rows.size, PANEL_MIN_ROWS) else rows.size
        val h = (rowHeight * rowCount + topTotal + bottomPad).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildBoxes()
    }

    private fun buildBoxes() {
        cancelTouches()
        boxes.clear()
        if (width <= 0 || height <= 0 || rows.isEmpty()) return

        val rowH = (height - topTotal - bottomPad) / rows.size

        rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (width - 2 * sidePad) / total
            val top = topTotal + r * rowH
            val bottom = topTotal + (r + 1) * rowH
            var x = sidePad
            val rowBoxes = ArrayList<KeyBox>()

            for (key in row) {
                val w = key.weight * unit
                if (key.type != KeyType.SPACER) {
                    val cell = RectF(x, top, x + w, bottom)
                    val face = RectF(x + hInset, top + vInset, x + w - hInset, bottom - vInset)
                    rowBoxes.add(KeyBox(key, cell, face))
                }
                x += w
            }

            if (rowBoxes.isNotEmpty()) {
                // Perluas area sentuh sampai tepi layar / antar baris supaya tidak ada area mati.
                rowBoxes.first().cell.left = 0f
                rowBoxes.last().cell.right = width.toFloat()
                // Baris pertama hanya diperluas sampai bawah bilah atas, bukan sampai tepi layar.
                if (r == 0) rowBoxes.forEach { it.cell.top = topExtra }
                if (r == rows.size - 1) rowBoxes.forEach { it.cell.bottom = height.toFloat() }
            }
            boxes.addAll(rowBoxes)
        }
    }

    // ----------------------------------------------------------------- Gambar

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        if (emojiOpen) {
            drawEmojiPanel(canvas)
            return
        }
        if (clipOpen) {
            drawClipboardPanel(canvas)
            return
        }
        if (editOpen) {
            drawEditPanel(canvas)
            return
        }
        if (trOpen) drawTranslateArea(canvas) else drawTopBars(canvas)
        if (trOpen && trPicking != 0) {
            // Memilih bahasa: daftar bahasa menggantikan tombol sementara.
            drawLanguagePicker(canvas)
        } else {
            for (box in boxes) drawKey(canvas, box)
            previewBox?.let { drawPreview(canvas, it) }
        }
        if (resizeOpen) drawResizeOverlay(canvas)
    }

    /** Tampilan sementara bilah atas: hanya penanda ruang, belum ada isi dan belum bisa disentuh. */
    private fun drawTopBars(canvas: Canvas) {
        if (barH <= 0f) return
        when (activeTopBar()) {
            TopBarMode.TOOLS -> drawToolsBar(canvas, 0f, barH)
            TopBarMode.SUGGEST -> drawSuggestionBar(canvas, 0f, barH)
            null -> Unit
        }
    }

    /** Isi bilah atas yang sedang tampil, atau null bila Tools dan kolom saran sama-sama mati. */
    private fun activeTopBar(): TopBarMode? = when {
        trOpen -> null
        toolsOn && suggestOn -> topMode
        toolsOn -> TopBarMode.TOOLS
        suggestOn -> TopBarMode.SUGGEST
        else -> null
    }

    /** Kotak ke-[index] di bilah Tools, atau null bila tidak muat. */
    private fun toolSlotRect(index: Int, top: Float, bottom: Float): RectF? {
        val margin = dp(8f)
        val gap = dp(10f)
        val side = (bottom - top) - dp(12f)
        if (side <= 0f) return null
        val fit = ((width - 2 * margin + gap) / (side + gap)).toInt()
        if (index >= fit.coerceIn(0, TOOLS_PLACEHOLDER_COUNT)) return null
        val left = margin + index * (side + gap)
        return RectF(left, top + dp(6f), left + side, bottom - dp(6f))
    }

    /**
     * Bilah Tools. Kotak pertama adalah papan klip (bisa diketuk); kotak lain masih kosong,
     * tempat alat berikutnya.
     */
    private fun drawToolsBar(canvas: Canvas, top: Float, bottom: Float) {
        for (i in 0 until TOOLS_PLACEHOLDER_COUNT) {
            val rect = toolSlotRect(i, top, bottom) ?: break
            val res = toolIcon(i)
            if (res != null) {
                fillPaint.color = if (toolPressed && toolDownSlot == i) palette.keyPressed else palette.key
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                drawIconAt(canvas, rect.centerX(), rect.centerY(), res, rect.width() * 0.56f)
            } else {
                fillPaint.color = (palette.key and 0x00FFFFFF) or (0x66 shl 24)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
        }
    }

    /** Ikon alat di kotak ke-[slot], atau null bila kotak itu belum berisi alat (atau alatnya dimatikan). */
    private fun toolIcon(slot: Int): Int? = when (slot) {
        0 -> R.drawable.ic_kb_clipboard
        1 -> if (cursorToolOn) R.drawable.ic_kb_cursor else null
        2 -> R.drawable.ic_kb_resize
        3 -> R.drawable.ic_kb_translate
        else -> null
    }

    /** Nomor kotak alat di titik sentuh (hanya yang bisa diketuk), atau -1. */
    private fun toolSlotAt(x: Float, y: Float): Int {
        if (barH <= 0f || activeTopBar() != TopBarMode.TOOLS || y < 0f || y >= barH) return -1
        val gap = dp(10f)
        for (i in 0 until TOOLS_PLACEHOLDER_COUNT) {
            if (toolIcon(i) == null) continue
            val rect = toolSlotRect(i, 0f, barH) ?: break
            if (x >= rect.left - gap / 2f && x <= rect.right + gap / 2f) return i
        }
        return -1
    }

    private fun openTool(slot: Int) {
        when (slot) {
            0 -> openClipboard()
            1 -> openEdit()
            2 -> openResize()
            3 -> openTranslate()
        }
    }

    /** Tiga kotak saran dipisah garis tipis. Saran terbaik di tengah, seperti Gboard. */
    private fun drawSuggestionBar(canvas: Canvas, top: Float, bottom: Float) {
        val cellW = width / 3f

        // Kotak yang sedang ditekan.
        if (barCell >= 0) {
            fillPaint.color = (palette.keyPressed and 0x00FFFFFF) or (0x99 shl 24)
            val rect = RectF(
                barCell * cellW + dp(4f), top + dp(4f),
                (barCell + 1) * cellW - dp(4f), bottom - dp(4f)
            )
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }

        strokePaint.color = (palette.text and 0x00FFFFFF) or (0x33 shl 24)
        strokePaint.strokeWidth = dp(1f)
        for (i in 1..2) {
            val x = cellW * i
            canvas.drawLine(x, top + dp(9f), x, bottom - dp(9f), strokePaint)
        }

        barTextPaint.textSize = (bottom - top) * 0.42f
        barTextPaint.color = palette.text
        val y = (top + bottom) / 2f - (barTextPaint.ascent() + barTextPaint.descent()) / 2f
        for (cell in 0 until SUGGESTION_COUNT) {
            val word = suggestionAt(cell) ?: continue
            val shown = TextUtils.ellipsize(
                word, barTextPaint, cellW - dp(16f), TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(shown, cellW * cell + cellW / 2f, y, barTextPaint)
        }
    }

    /** Saran untuk kotak [cell] (0 kiri, 1 tengah, 2 kanan), atau null bila kosong. */
    private fun suggestionAt(cell: Int): String? =
        suggestions.getOrNull(SLOT_ORDER[cell])?.takeIf { it.isNotEmpty() }

    /** Kotak saran di titik sentuh, atau -1 bila bukan di kolom saran atau kotaknya kosong. */
    private fun barCellAt(x: Float, y: Float): Int {
        if (barH <= 0f || activeTopBar() != TopBarMode.SUGGEST) return -1
        if (y < 0f || y >= barH) return -1
        val cell = (x / (width / SUGGESTION_COUNT.toFloat())).toInt().coerceIn(0, SUGGESTION_COUNT - 1)
        return if (suggestionAt(cell) == null) -1 else cell
    }

    private fun pickSuggestion(cell: Int) {
        val word = suggestionAt(cell) ?: return
        feedback(KeyType.CHARACTER)
        listener?.onSuggestionPick(word)
    }

    private fun drawKey(canvas: Canvas, box: KeyBox) {
        val key = box.key
        val face = box.face
        val shadowOffset = dp(1f)

        fillPaint.color = palette.shadow
        canvas.drawRoundRect(
            face.left, face.top + shadowOffset, face.right, face.bottom + shadowOffset,
            radius, radius, fillPaint
        )
        fillPaint.color = if (isHeld(box)) palette.keyPressed else palette.key
        canvas.drawRoundRect(face, radius, radius, fillPaint)

        when (key.type) {
            KeyType.CHARACTER -> {
                drawLabel(canvas, face, displayLabel(key), rowHeight * 0.42f)
                val alt = alternateFor(key)
                if (alt != null) {
                    drawHint(canvas, face, alt)
                } else if (isEmojiEntry(key)) {
                    drawHint(canvas, face, EMOJI_HINT)
                }
            }
            KeyType.TO_SYMBOLS, KeyType.TO_SYMBOLS_2, KeyType.TO_LETTERS ->
                drawLabel(canvas, face, key.label, rowHeight * 0.32f)
            KeyType.SHIFT -> drawIcon(
                canvas, face,
                when (shiftState) {
                    ShiftState.OFF -> R.drawable.ic_kb_shift
                    ShiftState.ONCE -> R.drawable.ic_kb_shift_on
                    ShiftState.LOCK -> R.drawable.ic_kb_caps
                }
            )
            KeyType.DELETE -> drawIcon(canvas, face, R.drawable.ic_kb_backspace)
            KeyType.ENTER -> drawIcon(canvas, face, enterIcon())
            else -> Unit
        }
    }

    private fun drawPreview(canvas: Canvas, box: KeyBox) {
        val face = box.face
        val pw = max(face.width() * 1.3f, dp(48f))
        val maxLeft = max(dp(2f), width - pw - dp(2f))
        val left = (face.centerX() - pw / 2f).coerceIn(dp(2f), maxLeft)
        val bottom = face.top - dp(2f)
        val rect = RectF(left, bottom - rowHeight, left + pw, bottom)

        fillPaint.color = palette.popup
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        strokePaint.color = palette.popupStroke
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        drawLabel(canvas, rect, displayLabel(box.key), rowHeight * 0.6f)
    }

    private fun drawHint(canvas: Canvas, face: RectF, text: String) {
        hintPaint.textSize = rowHeight * 0.22f
        hintPaint.color = palette.text
        hintPaint.alpha = 150
        val x = face.right - dp(5f)
        val y = face.top + dp(4f) - hintPaint.ascent()
        canvas.drawText(text, x, y, hintPaint)
    }

    private fun drawLabel(canvas: Canvas, rect: RectF, text: String, size: Float) {
        textPaint.textSize = size
        textPaint.color = palette.text
        val y = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, rect.centerX(), y, textPaint)
    }

    private fun drawIcon(canvas: Canvas, rect: RectF, res: Int) {
        val d = icon(res) ?: return
        val size = (rowHeight * 0.5f).toInt()
        val cx = rect.centerX().toInt()
        val cy = rect.centerY().toInt()
        d.setTint(palette.text)
        d.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        d.draw(canvas)
    }

    private fun icon(res: Int): Drawable? =
        icons.getOrPut(res) { ContextCompat.getDrawable(context, res)?.mutate() }

    private fun enterIcon(): Int = when (enterKind) {
        EnterKind.SEARCH -> R.drawable.ic_kb_search
        EnterKind.SEND -> R.drawable.ic_kb_send
        EnterKind.NEXT -> R.drawable.ic_kb_next
        EnterKind.DONE -> R.drawable.ic_kb_done
        EnterKind.ENTER -> R.drawable.ic_kb_enter
    }

    private fun displayLabel(key: Key): String =
        if (currentLayout == LayoutMode.LETTERS && shiftState != ShiftState.OFF) {
            key.label.uppercase()
        } else {
            key.label
        }

    private fun isHeld(box: KeyBox): Boolean {
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i) === box) return true
        }
        return false
    }

    // ---------------------------------------------------------------- Sentuhan

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (resizeOpen) return handleResizeTouch(event)
        if (emojiOpen) return handleEmojiTouch(event)
        if (clipOpen) return handleClipboardTouch(event)
        if (editOpen) return handleEditTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouches()
                handleDown(event.getPointerId(0), event.getX(0), event.getY(0))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                handleDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    handleMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handleUp(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTouches()
                invalidate()
            }
        }
        return true
    }

    private fun findBox(x: Float, y: Float): KeyBox? {
        if (boxes.isEmpty()) return null
        // Bilah atas (Tools keyboard dan kolom saran kata) bukan tombol. Dulu sentuhan di luar
        // tombol dipaksa masuk ke baris paling atas; sekarang area ini tidak memasukkan apa pun.
        if (y < topExtra) return null
        val px = x.coerceIn(0f, width - 1f)
        val py = y.coerceAtMost(height - 1f)
        for (box in boxes) {
            if (box.cell.contains(px, py)) return box
        }
        return null
    }

    private fun canPreview(box: KeyBox): Boolean =
        box.key.type == KeyType.CHARACTER && (box.face.top - dp(2f) - rowHeight) >= 0f

    private fun handleDown(id: Int, x: Float, y: Float) {
        // Terjemahan: kartu, bilah bahasa, dan daftar bahasa menangkap sentuhan; tombol qwerty tetap biasa.
        if (trOpen && (y < topExtra || trPicking != 0)) {
            trDown(id, x, y)
            return
        }
        // Sentuhan di bilah atas: hanya kotak saran yang bereaksi, tidak pernah tombol huruf.
        if (y < topExtra) {
            if (barPointer < 0 && toolPointer < 0) {
                if (activeTopBar() == TopBarMode.TOOLS) {
                    val slot = toolSlotAt(x, y)
                    if (slot >= 0) {
                        toolPointer = id
                        toolDownSlot = slot
                        toolPressed = true
                        invalidate()
                    }
                } else {
                    val cell = barCellAt(x, y)
                    if (cell >= 0) {
                        barPointer = id
                        barDownCell = cell
                        barCell = cell
                        invalidate()
                    }
                }
            }
            return
        }
        val box = findBox(x, y) ?: return
        pointers.put(id, box)
        feedback(box.key.type)
        if (box.key.type == KeyType.DELETE) {
            // Shift + hapus = gestur seleksi; selain itu hapus biasa.
            if (!startSelectGesture(id, x)) {
                listener?.onKeyDelete()
                repeatHandler.removeCallbacks(repeatRunnable)
                repeatHandler.postDelayed(repeatRunnable, longPressDelay)
            }
        } else {
            if (box.key.type == KeyType.SPACE && gestureSpaceOn) {
                spaceId = id
                spaceStartX = x
                spaceApplied = 0
                spaceActive = false
            }
            previewBox = if (canPreview(box)) box else null
            previewPointer = id
            if (box.key.type == KeyType.CHARACTER || isEmojiEntry(box.key)) scheduleAltPress(id, box)
        }
        invalidate()
    }

    private fun handleMove(id: Int, x: Float, y: Float) {
        if (id == trPointer) {
            trMove(x, y)
            return
        }
        if (id == toolPointer) {
            val inside = toolSlotAt(x, y) == toolDownSlot
            if (inside != toolPressed) {
                toolPressed = inside
                invalidate()
            }
            return
        }
        if (id == barPointer) {
            // Jari keluar dari kotak yang ditekan: batal, tidak ada saran yang dimasukkan.
            val next = if (barCellAt(x, y) == barDownCell) barDownCell else -1
            if (next != barCell) {
                barCell = next
                invalidate()
            }
            return
        }
        if (id == spaceId) {
            moveSpace(x)
            return
        }
        if (id == selDeleteId) {
            moveSelect(x)
            return
        }
        // Karakter tekan lama sudah dimasukkan: geseran berikutnya diabaikan.
        if (longFired.get(id)) return
        val current = pointers.get(id) ?: return
        if (current.key.type != KeyType.CHARACTER) return
        val next = findBox(x, y) ?: return
        if (next === current || next.key.type != KeyType.CHARACTER) return
        pointers.put(id, next)
        if (lpId == id) cancelAltPress()
        if (previewPointer == id) {
            previewBox = if (canPreview(next)) next else null
        }
        invalidate()
    }

    private fun handleUp(id: Int) {
        if (id == trPointer) {
            trUp()
            return
        }
        if (id == toolPointer) {
            val fire = toolPressed
            val slot = toolDownSlot
            toolPointer = -1
            toolDownSlot = -1
            toolPressed = false
            if (fire) {
                feedback(KeyType.CHARACTER)
                openTool(slot)
            }
            invalidate()
            return
        }
        if (id == barPointer) {
            val cell = barCell
            barPointer = -1
            barDownCell = -1
            barCell = -1
            if (cell >= 0) pickSuggestion(cell)
            invalidate()
            return
        }
        val box = pointers.get(id) ?: return
        pointers.remove(id)
        if (lpId == id) cancelAltPress()
        val fired = longFired.get(id)
        if (fired) longFired.delete(id)
        if (previewPointer == id) {
            previewBox = null
            previewPointer = -1
        }

        when {
            // Karakter alternatif sudah dimasukkan saat ditekan lama: tidak mengetik lagi.
            fired -> Unit
            id == spaceId -> {
                val moved = spaceActive
                resetSpaceGesture()
                // Hanya diketuk tanpa geser: masukkan satu spasi seperti biasa.
                if (!moved) perform(box.key)
            }
            id == selDeleteId -> {
                // Jari hapus dilepas: hapus blok hanya bila Shift masih ditahan.
                val shiftStillHeld = selShiftId >= 0 && pointers.indexOfKey(selShiftId) >= 0
                val commit = selActive && shiftStillHeld
                val cancel = selActive && !shiftStillHeld
                selDeleteId = -1
                selActive = false
                selLast = 0
                if (commit) listener?.onSelectCommit()
                if (cancel) listener?.onSelectCancel()
            }
            id == selShiftId -> {
                // Shift dilepas: batalkan seleksi, jangan hapus, dan jangan ubah status shift.
                selShiftId = -1
                cancelSelection()
            }
            box.key.type == KeyType.DELETE -> repeatHandler.removeCallbacks(repeatRunnable)
            else -> perform(box.key)
        }
        invalidate()
    }

    private fun cancelTouches() {
        repeatHandler.removeCallbacks(repeatRunnable)
        cancelSelection()
        resetSpaceGesture()
        selDeleteId = -1
        selShiftId = -1
        selLast = 0
        cancelAltPress()
        longFired.clear()
        pointers.clear()
        barPointer = -1
        barDownCell = -1
        barCell = -1
        toolPointer = -1
        toolDownSlot = -1
        toolPressed = false
        trPointer = -1
        trTarget = -1
        trScrolling = false
        previewBox = null
        previewPointer = -1
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTouches()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            cancelTouches()
            invalidate()
        }
    }

    // ------------------------------------------------- Karakter tekan lama

    /** Angka tekan lama (Q-P jadi 1-0); null bila bukan huruf Q-P atau fiturnya mati. */
    private fun alternateFor(key: Key): String? {
        if (currentLayout != LayoutMode.LETTERS || key.type != KeyType.CHARACTER) return null
        // Baris angka dan tekan lama angka tidak pernah menyala bersamaan (dijaga di Prefs).
        if (numberRowOn || !longPressNumbersOn) return null
        val c = key.label.singleOrNull() ?: return null
        return KeyAlternates.NUMBER[c]
    }

    private fun scheduleAltPress(id: Int, box: KeyBox) {
        cancelAltPress()
        if (alternateFor(box.key) == null && !isEmojiEntry(box.key)) return
        lpId = id
        lpBox = box
        repeatHandler.postDelayed(lpRunnable, longPressDelay)
    }

    private fun cancelAltPress() {
        repeatHandler.removeCallbacks(lpRunnable)
        lpId = -1
        lpBox = null
    }

    private fun fireAltPress() {
        val id = lpId
        val box = lpBox
        lpId = -1
        lpBox = null
        if (box == null || id < 0) return
        // Jari sudah pindah atau lepas: tidak ada yang dimasukkan.
        if (pointers.get(id) !== box) return

        // Tekan lama tombol emoji: buka panel emoji (lamanya sama dengan Durasi tekan lama).
        if (isEmojiEntry(box.key)) {
            longFired.put(id, true)
            if (vibrateOn) vibrate()
            openEmoji()
            return
        }

        val alt = alternateFor(box.key) ?: return

        longFired.put(id, true)
        if (vibrateOn) vibrate()
        if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
        listener?.onKeyText(alt)
        if (previewPointer == id) previewBox = null
        invalidate()
    }

    // -------------------------------------------------------------------- Emoji

    /**
     * Tombol yang membuka panel emoji bila ditekan lama: tombol koma, atau tombol 123 / ABC
     * bila koma disembunyikan di layar huruf.
     */
    private fun isEmojiEntry(key: Key): Boolean {
        if (!emojiOn) return false
        val hasComma = currentLayout != LayoutMode.LETTERS || commaOn
        return if (hasComma) {
            key.type == KeyType.CHARACTER && key.label == ","
        } else {
            key.type == KeyType.TO_SYMBOLS || key.type == KeyType.TO_LETTERS
        }
    }

    private fun openEmoji() {
        if (emojiOpen) return
        closeResize()
        closeTranslate()
        closeClipboard()
        closeEdit()
        cancelTouches()
        if (emojiLists == null) {
            // Buang emoji yang tidak punya gambar di font perangkat ini.
            emojiLists = EmojiData.categories.map { c -> c.items.filter { emojiPaint.hasGlyph(it) } }
        }
        emojiRecents = prefs.emojiRecents.split(' ').filter { it.isNotEmpty() && emojiPaint.hasGlyph(it) }
        emojiTab = if (emojiRecents.isNotEmpty()) 0 else 1
        emojiScroll = 0f
        emojiPressedIndex = -1
        panelPressedBottom = -1
        panelTouchActive = false
        emojiOpen = true
        requestLayout()
        invalidate()
    }

    /** Tutup panel emoji dan kembali ke tombol. */
    fun closeEmoji() {
        if (!emojiOpen) return
        emojiOpen = false
        panelCancel()
        requestLayout()
        invalidate()
    }

    private fun currentEmojiList(): List<String> =
        if (emojiTab == 0) emojiRecents else emojiLists?.getOrNull(emojiTab - 1) ?: emptyList()

    private fun emojiTabCount(): Int = EmojiData.categories.size + 1

    private fun panelGeo(): PanelGeo {
        if (clipOpen || editOpen) {
            // Panel Tools: bilah atas (kembali, nama, aksi), tanpa baris ABC, spasi, dan hapus di bawah.
            val tabsTop = dp(4f)
            val tabsBottom = tabsTop + rowHeight * 0.8f
            val gridTop = if (clipOpen) {
                tabsBottom + dp(4f) + clipModeHeight() + dp(6f)
            } else {
                tabsBottom + dp(8f)
            }
            val gridBottom = height - bottomPad - dp(4f)
            return PanelGeo(tabsTop, tabsBottom, gridTop, gridBottom, gridBottom, gridBottom, 6, width / 6f)
        }
        val tabsTop = dp(4f)
        val tabsBottom = tabsTop + rowHeight * 0.8f
        val bottomBottom = height - bottomPad
        val bottomTop = bottomBottom - rowHeight
        val gridTop = tabsBottom + dp(2f)
        val gridBottom = bottomTop - dp(2f)
        val cols = max(6, (width / dp(46f)).toInt())
        return PanelGeo(
            tabsTop, tabsBottom, gridTop, gridBottom, bottomTop, bottomBottom,
            cols, width / cols.toFloat()
        )
    }

    private fun emojiScrollMax(g: PanelGeo, count: Int): Float {
        val content = ceil(count / g.cols.toFloat()) * g.cell
        return max(0f, content - (g.gridBottom - g.gridTop))
    }

    private fun emojiTabAt(x: Float): Int =
        (x / (width / emojiTabCount().toFloat())).toInt().coerceIn(0, emojiTabCount() - 1)

    /** Tombol bawah: 0 = ABC, 1 = spasi, 2 = hapus (lebar 2 : 6 : 2). */
    private fun panelBottomIndex(x: Float): Int {
        val unit = (width - 2 * sidePad) / 10f
        return when {
            x < sidePad + 2 * unit -> 0
            x < sidePad + 8 * unit -> 1
            else -> 2
        }
    }

    private fun emojiIndexAt(x: Float, y: Float, g: PanelGeo): Int {
        val col = (x / g.cell).toInt()
        if (col !in 0 until g.cols) return -1
        val row = ((y - g.gridTop + emojiScroll) / g.cell).toInt()
        return row * g.cols + col
    }

    private fun drawEmojiPanel(canvas: Canvas) {
        val g = panelGeo()

        // Tab kategori.
        val tabW = width / emojiTabCount().toFloat()
        emojiPaint.textSize = (g.tabsBottom - g.tabsTop) * 0.52f
        val tabBase = (g.tabsTop + g.tabsBottom) / 2f - (emojiPaint.ascent() + emojiPaint.descent()) / 2f
        for (i in 0 until emojiTabCount()) {
            val left = tabW * i
            if (i == emojiTab) {
                fillPaint.color = palette.key
                val rect = RectF(left + dp(2f), g.tabsTop, left + tabW - dp(2f), g.tabsBottom)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
            val icon = if (i == 0) EMOJI_RECENT_ICON else EmojiData.categories[i - 1].icon
            canvas.drawText(icon, left + tabW / 2f, tabBase, emojiPaint)
        }

        // Kisi emoji (bisa digulir ke atas dan bawah).
        val list = currentEmojiList()
        canvas.save()
        canvas.clipRect(0f, g.gridTop, width.toFloat(), g.gridBottom)
        if (list.isEmpty()) {
            barTextPaint.textSize = dp(14f)
            barTextPaint.color = palette.text
            barTextPaint.alpha = 150
            val y = (g.gridTop + g.gridBottom) / 2f - (barTextPaint.ascent() + barTextPaint.descent()) / 2f
            canvas.drawText(context.getString(R.string.emoji_empty), width / 2f, y, barTextPaint)
        } else {
            emojiPaint.textSize = g.cell * 0.58f
            val base = -(emojiPaint.ascent() + emojiPaint.descent()) / 2f
            val firstRow = (emojiScroll / g.cell).toInt()
            val lastRow = ((emojiScroll + (g.gridBottom - g.gridTop)) / g.cell).toInt()
            for (row in firstRow..lastRow) {
                for (col in 0 until g.cols) {
                    val index = row * g.cols + col
                    if (index >= list.size) break
                    val cx = g.cell * col + g.cell / 2f
                    val cy = g.gridTop + row * g.cell - emojiScroll + g.cell / 2f
                    if (index == emojiPressedIndex) {
                        fillPaint.color = palette.key
                        val half = g.cell / 2f - dp(2f)
                        canvas.drawRoundRect(
                            RectF(cx - half, cy - half, cx + half, cy + half), radius, radius, fillPaint
                        )
                    }
                    canvas.drawText(list[index], cx, cy + base, emojiPaint)
                }
            }
        }
        canvas.restore()

        drawPanelBottomRow(canvas, g)
    }

    /** Baris bawah yang sama di panel emoji dan papan klip: ABC, spasi, hapus. */
    private fun drawPanelBottomRow(canvas: Canvas, g: PanelGeo) {
        val unit = (width - 2 * sidePad) / 10f
        val weights = floatArrayOf(2f, 6f, 2f)
        var x = sidePad
        for (i in 0..2) {
            val w = weights[i] * unit
            val face = RectF(x + hInset, g.bottomTop + vInset, x + w - hInset, g.bottomBottom - vInset)
            drawPanelFace(canvas, face, panelPressedBottom == i)
            when (i) {
                0 -> drawLabel(canvas, face, "ABC", rowHeight * 0.32f)
                2 -> drawIcon(canvas, face, R.drawable.ic_kb_backspace)
            }
            x += w
        }
    }

    private fun drawPanelFace(canvas: Canvas, face: RectF, pressed: Boolean) {
        val shadowOffset = dp(1f)
        fillPaint.color = palette.shadow
        canvas.drawRoundRect(
            face.left, face.top + shadowOffset, face.right, face.bottom + shadowOffset,
            radius, radius, fillPaint
        )
        fillPaint.color = if (pressed) palette.keyPressed else palette.key
        canvas.drawRoundRect(face, radius, radius, fillPaint)
    }

    // ------------------------------------------------- Baris bawah panel (dipakai bersama)

    /** Tombol bawah ditekan: ABC, spasi (bisa digeser untuk memindah kursor), atau hapus. */
    private fun panelBottomDown(x: Float) {
        val b = panelBottomIndex(x)
        panelTarget = b
        panelPressedBottom = b
        feedback(
            when (b) {
                1 -> KeyType.SPACE
                2 -> KeyType.DELETE
                else -> KeyType.TO_LETTERS
            }
        )
        when (b) {
            1 -> {
                // Spasi di panel punya gestur geser kursor yang sama dengan spasi di keyboard.
                spaceStartX = x
                spaceApplied = 0
                spaceActive = false
            }
            2 -> {
                listener?.onKeyDelete()
                repeatHandler.removeCallbacks(repeatRunnable)
                repeatHandler.postDelayed(repeatRunnable, longPressDelay)
            }
        }
    }

    private fun panelBottomMove(x: Float) {
        if (panelTarget == 1 && gestureSpaceOn) moveSpace(x)
    }

    private fun panelBottomUp(x: Float, onBack: () -> Unit) {
        val b = panelBottomIndex(x)
        when (panelTarget) {
            2 -> repeatHandler.removeCallbacks(repeatRunnable)
            0 -> if (b == 0) onBack()
            1 -> {
                // Hanya diketuk tanpa geser: masukkan satu spasi seperti biasa.
                val moved = spaceActive
                resetSpaceGesture()
                if (!moved && b == 1) listener?.onKeyText(" ")
            }
        }
    }

    /** Bersihkan status sentuhan panel (emoji maupun papan klip). */
    private fun panelReset() {
        panelRegion = PANEL_NONE
        panelTarget = -1
        panelPressedBottom = -1
        emojiPressedIndex = -1
        editTopTarget = -1
        clipDownIndex = -1
        clipHeaderTarget = -1
        shotPromptPressed = false
        editDownButton = -1
        editPressedButton = -1
        invalidate()
    }

    private fun panelCancel() {
        repeatHandler.removeCallbacks(repeatRunnable)
        repeatHandler.removeCallbacks(editRepeatRunnable)
        repeatHandler.removeCallbacks(clipLpRunnable)
        resetSpaceGesture()
        panelTouchActive = false
        panelReset()
    }

    // ------------------------------------------------------------ Sentuhan panel emoji

    /**
     * Sentuhan di panel emoji (satu jari). Jari yang masih menekan tombol koma saat panel terbuka
     * diabaikan sampai dilepas, karena sentuhannya dimulai sebelum panel ada.
     */
    private fun handleEmojiTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> emojiDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> if (panelTouchActive) emojiMove(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                if (panelTouchActive) emojiUp(event.x, event.y)
                panelTouchActive = false
            }
            MotionEvent.ACTION_CANCEL -> panelCancel()
        }
        return true
    }

    private fun emojiDown(x: Float, y: Float) {
        val g = panelGeo()
        panelTouchActive = true
        panelDownY = y
        panelLastY = y
        panelScrolling = false
        emojiPressedIndex = -1
        panelPressedBottom = -1
        when {
            y >= g.gridBottom -> {
                panelRegion = PANEL_BOTTOM
                panelBottomDown(x)
            }
            y < g.gridTop -> {
                panelRegion = PANEL_TAB
                panelTarget = emojiTabAt(x)
            }
            else -> {
                panelRegion = PANEL_GRID
                panelTarget = -1
                val index = emojiIndexAt(x, y, g)
                if (index in currentEmojiList().indices) emojiPressedIndex = index
            }
        }
        invalidate()
    }

    private fun emojiMove(x: Float, y: Float) {
        when (panelRegion) {
            PANEL_BOTTOM -> panelBottomMove(x)
            PANEL_GRID -> {
                if (!panelScrolling && abs(y - panelDownY) > touchSlop) {
                    panelScrolling = true
                    emojiPressedIndex = -1
                }
                if (panelScrolling) {
                    val g = panelGeo()
                    val limit = emojiScrollMax(g, currentEmojiList().size)
                    emojiScroll = (emojiScroll - (y - panelLastY)).coerceIn(0f, limit)
                    invalidate()
                }
                panelLastY = y
            }
        }
    }

    private fun emojiUp(x: Float, y: Float) {
        val g = panelGeo()
        when (panelRegion) {
            PANEL_TAB -> {
                val tab = emojiTabAt(x)
                if (tab == panelTarget && tab != emojiTab) {
                    emojiTab = tab
                    emojiScroll = 0f
                }
            }
            PANEL_BOTTOM -> panelBottomUp(x) { closeEmoji() }
            PANEL_GRID -> if (!panelScrolling) {
                val list = currentEmojiList()
                val index = emojiIndexAt(x, y, g)
                if (index in list.indices) commitEmoji(list[index])
            }
        }
        if (emojiOpen) panelReset()
    }

    private fun commitEmoji(emoji: String) {
        feedback(KeyType.CHARACTER)
        listener?.onKeyText(emoji)
        // Disimpan sebagai terbaru; tab Terbaru baru berubah saat panel dibuka lagi.
        val updated = (listOf(emoji) + prefs.emojiRecents.split(' ').filter { it.isNotEmpty() && it != emoji })
            .take(EMOJI_MAX_RECENTS)
        prefs.emojiRecents = updated.joinToString(" ")
    }

    // ---------------------------------------------------------------- Papan klip

    private fun openClipboard() {
        if (clipOpen) return
        closeResize()
        closeTranslate()
        closeEmoji()
        closeEdit()
        cancelTouches()
        ClipboardStore.expire(prefs.clipMaxAgeMs())
        clipItems = ClipboardStore.items()
        clipScroll = 0f
        clipMode = 0
        shotScroll = 0f
        clipSelecting = false
        clipSelected.clear()
        shotSelected.clear()
        panelTouchActive = false
        clipOpen = true
        requestLayout()
        invalidate()
    }

    /** Tutup papan klip dan kembali ke tombol. */
    fun closeClipboard() {
        if (!clipOpen) return
        clipOpen = false
        clipSelecting = false
        clipSelected.clear()
        shotSelected.clear()
        panelCancel()
        requestLayout()
        invalidate()
    }

    private fun clipCardHeight(): Float = max(dp(52f), rowHeight * 1.05f)
    private fun clipGap(): Float = dp(6f)
    private fun clipMargin(): Float = dp(8f)
    private fun clipButtonWidth(): Float = dp(40f)

    private fun clipScrollMax(g: PanelGeo): Float {
        val pitch = clipCardHeight() + clipGap()
        return max(0f, clipItems.size * pitch + clipGap() - (g.gridBottom - g.gridTop))
    }

    private fun clipItemTop(index: Int, g: PanelGeo): Float =
        g.gridTop + clipGap() + index * (clipCardHeight() + clipGap()) - clipScroll

    /** Nomor kartu di ketinggian [y], atau -1 bila di sela antar kartu. */
    private fun clipItemAt(y: Float, g: PanelGeo): Int {
        val pitch = clipCardHeight() + clipGap()
        val rel = y - g.gridTop - clipGap() + clipScroll
        if (rel < 0f) return -1
        val index = (rel / pitch).toInt()
        return if (rel - index * pitch <= clipCardHeight() && index < clipItems.size) index else -1
    }

    /** Bagian kartu yang disentuh: 0 = kartu, 1 = tombol sematkan, 2 = tombol hapus. */
    private fun clipPartAt(x: Float): Int {
        if (clipSelecting) return 0
        val right = width - clipMargin()
        return when {
            x >= right - clipButtonWidth() -> 2
            x >= right - 2 * clipButtonWidth() -> 1
            else -> 0
        }
    }

    private fun drawClipboardPanel(canvas: Canvas) {
        val g = panelGeo()
        val selecting = clipSelecting

        // Bilah atas: kembali (atau batal memilih), nama, lalu aksi di kanan: tambah, sematkan, hapus.
        drawToolBarButton(
            canvas, toolBarBackRect(g),
            if (selecting) R.drawable.ic_kb_close else R.drawable.ic_kb_back,
            true, panelTouchActive && clipHeaderTarget == CLIP_T_BACK
        )
        val title = if (selecting) {
            val count = if (clipMode == 0) clipSelected.size else shotSelected.size
            context.getString(R.string.clip_selected_fmt, count)
        } else {
            context.getString(R.string.clip_title)
        }
        drawToolBarTitle(canvas, g, title)
        if (!selecting) {
            drawToolBarButton(
                canvas, toolBarActionRect(2, g), R.drawable.ic_kb_plus,
                clipMode == 0, panelTouchActive && clipHeaderTarget == 2
            )
        }
        // Ikon berubah: Lepas sematan bila semua yang dipilih sudah disematkan, selain itu Sematkan.
        drawToolBarButton(
            canvas, toolBarActionRect(1, g),
            if (allSelectedPinned()) R.drawable.ic_kb_unpin else R.drawable.ic_kb_pin,
            selecting, panelTouchActive && clipHeaderTarget == 1
        )
        drawToolBarButton(
            canvas, toolBarActionRect(0, g), R.drawable.ic_kb_trash,
            selecting, panelTouchActive && clipHeaderTarget == 0
        )

        // Mode: teks atau screenshot, hanya ikon.
        for (i in 0..1) {
            val r = clipModeRect(i, g)
            val active = clipMode == i
            if (active) {
                fillPaint.color = palette.key
                canvas.drawRoundRect(r, radius, radius, fillPaint)
            }
            val res = if (i == 0) R.drawable.ic_kb_mode_text else R.drawable.ic_kb_mode_image
            val a = when {
                selecting -> 70
                active -> 255
                else -> 150
            }
            drawIconAt(canvas, r.centerX(), r.centerY(), res, r.height() * 0.62f, a)
        }

        // Isi: daftar teks atau kisi screenshot (bisa digulir).
        canvas.save()
        canvas.clipRect(0f, g.gridTop, width.toFloat(), g.gridBottom)
        if (clipMode == 0) drawClipTextList(canvas, g) else drawShotArea(canvas, g)
        canvas.restore()
    }

    private fun drawClipTextList(canvas: Canvas, g: PanelGeo) {
        val margin = clipMargin()
        if (clipItems.isEmpty()) {
            drawCenteredMessage(canvas, g, context.getString(R.string.clip_empty), 0f)
        } else {
            val cardH = clipCardHeight()
            val btnW = clipButtonWidth()
            for (i in clipItems.indices) {
                val top = clipItemTop(i, g)
                if (top + cardH < g.gridTop || top > g.gridBottom) continue
                drawClipCard(canvas, clipItems[i], i, top, cardH, margin, btnW)
            }
        }
    }

    /** Pesan di tengah daftar, digeser [offsetY] ke bawah dari tengah. */
    private fun drawCenteredMessage(canvas: Canvas, g: PanelGeo, text: String, offsetY: Float) {
        clipTextPaint.textSize = dp(14f)
        clipTextPaint.color = palette.text
        clipTextPaint.alpha = 150
        val w = (width - 2 * dp(32f)).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, clipTextPaint, w)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(dp(32f), (g.gridTop + g.gridBottom) / 2f - layout.height / 2f + offsetY)
        layout.draw(canvas)
        canvas.restore()
        clipTextPaint.alpha = 255
    }

    // ------------------------------------------------------------ Mode screenshot

    // ------------------------------------------------------------ Bilah atas Tools

    private fun RectF.padded(by: Float): RectF = RectF(left - by, top - by, right + by, bottom + by)

    private fun toolBarSize(g: PanelGeo): Float = g.tabsBottom - g.tabsTop

    /** Tombol kembali di kiri bilah atas. */
    private fun toolBarBackRect(g: PanelGeo): RectF {
        val size = toolBarSize(g)
        return RectF(clipMargin(), g.tabsTop, clipMargin() + size, g.tabsBottom)
    }

    /** Tombol aksi di kanan bilah atas; [fromRight] 0 = paling kanan. */
    private fun toolBarActionRect(fromRight: Int, g: PanelGeo): RectF {
        val size = toolBarSize(g)
        val right = width - clipMargin() - fromRight * (size + dp(6f))
        return RectF(right - size, g.tabsTop, right, g.tabsBottom)
    }

    private fun drawToolBarButton(canvas: Canvas, rect: RectF, iconRes: Int, enabled: Boolean, pressed: Boolean) {
        drawPanelFace(canvas, rect, pressed && enabled)
        drawIconAt(canvas, rect.centerX(), rect.centerY(), iconRes, rect.width() * 0.5f, if (enabled) 255 else 70)
    }

    /** Nama Tools di sebelah kanan tombol kembali. */
    private fun drawToolBarTitle(canvas: Canvas, g: PanelGeo, title: String) {
        headerPaint.textSize = toolBarSize(g) * 0.46f
        headerPaint.color = palette.text
        headerPaint.alpha = 255
        val y = (g.tabsTop + g.tabsBottom) / 2f - (headerPaint.ascent() + headerPaint.descent()) / 2f
        canvas.drawText(title, toolBarBackRect(g).right + dp(10f), y, headerPaint)
    }

    private fun clipModeHeight(): Float = rowHeight * 0.7f

    /** Tombol mode (ikon saja): 0 = teks, 1 = screenshot, di bawah bilah atas. */
    private fun clipModeRect(index: Int, g: PanelGeo): RectF {
        val h = clipModeHeight()
        val w = h * 1.5f
        val left = clipMargin() + index * (w + dp(6f))
        val top = g.tabsBottom + dp(4f)
        return RectF(left, top, left + w, top + h)
    }

    /** Sasaran ketuk di bilah atas atau baris mode, atau -1. */
    private fun clipTopTargetAt(x: Float, y: Float, g: PanelGeo): Int {
        if (y < g.tabsBottom + dp(2f)) {
            if (toolBarBackRect(g).padded(dp(3f)).contains(x, y)) return CLIP_T_BACK
            for (a in 0..2) {
                if (toolBarActionRect(a, g).padded(dp(3f)).contains(x, y)) return a
            }
            return -1
        }
        for (i in 0..1) {
            if (clipModeRect(i, g).padded(dp(3f)).contains(x, y)) return CLIP_TARGET_MODE + i
        }
        return -1
    }

    /** Semua item yang dipilih sudah disematkan (dipakai untuk memilih ikon Sematkan atau Lepas sematan). */
    private fun allSelectedPinned(): Boolean = if (clipMode == 0) {
        val chosen = clipItems.filter { it.id in clipSelected }
        chosen.isNotEmpty() && chosen.all { it.pinned }
    } else {
        val chosen = shotItems.filter { it.id in shotSelected }
        chosen.isNotEmpty() && chosen.all { it.pinned }
    }

    private fun clipTopAction(target: Int) {
        when {
            target == CLIP_T_BACK -> if (clipSelecting) clipHeaderAction(2) else closeClipboard()
            target == 0 -> if (clipSelecting) clipHeaderAction(1)
            target == 1 -> if (clipSelecting) clipHeaderAction(0)
            target == 2 -> if (!clipSelecting && clipMode == 0) openAddClip()
            target >= CLIP_TARGET_MODE -> if (!clipSelecting) setClipMode(target - CLIP_TARGET_MODE)
        }
    }

    /** Buka kotak isian untuk menambah teks baru ke papan klip. */
    private fun openAddClip() {
        closeClipboard()
        val intent = Intent(context, AddClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Tidak bisa membuka kotak isian: abaikan.
        }
    }

    private fun setClipMode(mode: Int) {
        if (clipMode == mode) return
        clipMode = mode
        clipSelecting = false
        clipSelected.clear()
        shotSelected.clear()
        if (mode == 1) loadShots() else refreshClipItems()
        invalidate()
    }

    /**
     * Muat screenshot terbaru. Batas waktu: sejak fitur dinyalakan (supaya screenshot lama tidak ikut),
     * dan bila hapus otomatis menyala, tidak lebih tua dari lama yang dipilih. Yang disematkan tetap tampil.
     */
    private fun loadShots() {
        shotScroll = 0f
        shotReady = prefs.clipScreenshots && ScreenshotStore.hasPermission(context)
        shotLoaded = false
        if (!shotReady) {
            shotItems = emptyList()
            invalidate()
            return
        }
        var start = prefs.screenshotSinceMs / 1000L
        val maxAge = prefs.clipMaxAgeMs()
        if (maxAge > 0L) start = max(start, System.currentTimeMillis() / 1000L - maxAge / 1000L)
        ScreenshotStore.load(context, start) { list ->
            if (clipOpen && clipMode == 1) {
                shotItems = list
                shotLoaded = true
                shotScroll = shotScroll.coerceIn(0f, shotScrollMax(panelGeo()))
                invalidate()
            }
        }
    }

    private fun refreshShots() {
        shotItems = ScreenshotStore.order(shotItems)
        shotScroll = shotScroll.coerceIn(0f, shotScrollMax(panelGeo()))
        invalidate()
    }

    private class ShotGeo(val cols: Int, val cellW: Float, val cellH: Float, val margin: Float, val gap: Float)

    private fun shotGeo(g: PanelGeo): ShotGeo {
        val margin = dp(8f)
        val gap = dp(6f)
        val cols = max(3, (width / dp(120f)).toInt())
        val cellW = (width - 2 * margin - (cols - 1) * gap) / cols
        val cellH = minOf(cellW * 1.5f, (g.gridBottom - g.gridTop) * 0.85f)
        return ShotGeo(cols, cellW, cellH, margin, gap)
    }

    private fun shotCellRect(index: Int, sg: ShotGeo, g: PanelGeo): RectF {
        val row = index / sg.cols
        val col = index % sg.cols
        val left = sg.margin + col * (sg.cellW + sg.gap)
        val top = g.gridTop + sg.gap + row * (sg.cellH + sg.gap) - shotScroll
        return RectF(left, top, left + sg.cellW, top + sg.cellH)
    }

    private fun shotScrollMax(g: PanelGeo): Float {
        val sg = shotGeo(g)
        val rows = (shotItems.size + sg.cols - 1) / sg.cols
        val content = rows * (sg.cellH + sg.gap) + sg.gap
        return max(0f, content - (g.gridBottom - g.gridTop))
    }

    private fun shotIndexAt(x: Float, y: Float, g: PanelGeo): Int {
        val sg = shotGeo(g)
        for (i in shotItems.indices) {
            val r = shotCellRect(i, sg, g)
            if (r.bottom < g.gridTop) continue
            if (r.top > g.gridBottom) break
            if (r.contains(x, y)) return i
        }
        return -1
    }

    /** Bagian screenshot yang disentuh: 0 = gambar, 1 = tombol sematkan (kiri atas), 2 = tombol hapus (kanan atas). */
    private fun shotPartAt(x: Float, y: Float, index: Int, g: PanelGeo): Int {
        if (clipSelecting) return 0
        val r = shotCellRect(index, shotGeo(g), g)
        val reach = dp(20f)
        return when {
            x <= r.left + 2 * reach && y <= r.top + 2 * reach -> 1
            x >= r.right - 2 * reach && y <= r.top + 2 * reach -> 2
            else -> 0
        }
    }

    private fun shotPromptRect(g: PanelGeo): RectF {
        val w = dp(180f)
        val h = dp(44f)
        val cx = width / 2f
        val cy = (g.gridTop + g.gridBottom) / 2f + dp(44f)
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    private fun drawShotArea(canvas: Canvas, g: PanelGeo) {
        if (!shotReady) {
            // Belum ada izin atau fitur belum dinyalakan: ajak memberi izin.
            drawCenteredMessage(canvas, g, context.getString(R.string.shot_need_access), -dp(24f))
            val r = shotPromptRect(g)
            drawPanelFace(canvas, r, shotPromptPressed)
            headerPaint.textSize = rowHeight * 0.3f
            headerPaint.color = palette.text
            val label = context.getString(R.string.shot_grant)
            val x = r.centerX() - headerPaint.measureText(label) / 2f
            val y = r.centerY() - (headerPaint.ascent() + headerPaint.descent()) / 2f
            canvas.drawText(label, x, y, headerPaint)
            return
        }
        if (!shotLoaded) return
        if (shotItems.isEmpty()) {
            drawCenteredMessage(canvas, g, context.getString(R.string.shot_empty), 0f)
            return
        }
        val sg = shotGeo(g)
        for (i in shotItems.indices) {
            val r = shotCellRect(i, sg, g)
            if (r.bottom < g.gridTop) continue
            if (r.top > g.gridBottom) break
            drawShotCell(canvas, shotItems[i], i, r)
        }
    }

    private fun drawShotCell(canvas: Canvas, item: ShotItem, index: Int, r: RectF) {
        val selected = item.id in shotSelected
        val bitmap = ShotThumbs.get(item.id)
        canvas.save()
        val clip = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        if (bitmap != null) {
            // Isi kotak penuh dari tengah gambar (center crop).
            val scale = max(r.width() / bitmap.width, r.height() / bitmap.height)
            val srcW = r.width() / scale
            val srcH = r.height() / scale
            val srcL = (bitmap.width - srcW) / 2f
            val srcT = (bitmap.height - srcH) / 2f
            val src = Rect(srcL.toInt(), srcT.toInt(), (srcL + srcW).toInt(), (srcT + srcH).toInt())
            canvas.drawBitmap(bitmap, src, r, bitmapPaint)
        } else {
            fillPaint.color = palette.key
            canvas.drawRect(r, fillPaint)
            ShotThumbs.request(context, item) { invalidate() }
        }
        val pressed = panelTouchActive && clipDownIndex == index && clipDownPart == 0 &&
            !panelScrolling && clipMode == 1
        if (pressed) {
            fillPaint.color = 0x33FFFFFF
            canvas.drawRect(r, fillPaint)
        }
        canvas.restore()

        if (item.pinned) {
            // Disematkan: bingkai berwarna tebal. Screenshot biasa tidak punya bingkai ini.
            strokePaint.color = PIN_COLOR
            strokePaint.strokeWidth = dp(3f)
            canvas.drawRoundRect(r.padded(-dp(1.5f)), radius, radius, strokePaint)
        }
        if (selected) {
            strokePaint.color = palette.text
            strokePaint.strokeWidth = dp(2f)
            canvas.drawRoundRect(r.padded(-dp(5f)), radius, radius, strokePaint)
        }

        val white = 0xFFFFFFFF.toInt()
        val circle = dp(14f)
        val leftCx = r.left + dp(20f)
        val rightCx = r.right - dp(20f)
        val topCy = r.top + dp(20f)
        if (clipSelecting) {
            // Kotak pilih di kanan atas; lencana pin berwarna di kiri atas hanya untuk yang disematkan.
            if (selected) {
                fillPaint.color = palette.text
                canvas.drawCircle(rightCx, topCy, circle, fillPaint)
                drawIconAt(canvas, rightCx, topCy, R.drawable.ic_kb_check, circle * 1.2f, tint = palette.key)
            } else {
                canvas.drawCircle(rightCx, topCy, circle, overlayPaint)
                strokePaint.color = white
                strokePaint.strokeWidth = dp(1.5f)
                canvas.drawCircle(rightCx, topCy, circle, strokePaint)
            }
            if (item.pinned) {
                fillPaint.color = PIN_COLOR
                canvas.drawCircle(leftCx, topCy, circle, fillPaint)
                drawIconAt(canvas, leftCx, topCy, R.drawable.ic_kb_pin, circle * 1.1f, tint = white)
            }
        } else {
            // Kiri atas: tombol sematkan. Disematkan = lingkaran berwarna dengan ikon Lepas sematan;
            // belum = lingkaran gelap dengan ikon Pin. Kanan atas: tombol hapus.
            if (item.pinned) {
                fillPaint.color = PIN_COLOR
                canvas.drawCircle(leftCx, topCy, circle, fillPaint)
                drawIconAt(canvas, leftCx, topCy, R.drawable.ic_kb_unpin, circle * 1.1f, 255, white)
            } else {
                canvas.drawCircle(leftCx, topCy, circle, overlayPaint)
                drawIconAt(canvas, leftCx, topCy, R.drawable.ic_kb_pin, circle * 1.1f, 210, white)
            }
            canvas.drawCircle(rightCx, topCy, circle, overlayPaint)
            drawIconAt(canvas, rightCx, topCy, R.drawable.ic_kb_trash, circle * 1.1f, 230, white)
        }
    }

    private fun openScreenshotAccess() {
        closeClipboard()
        val intent = Intent(context, ScreenshotAccessActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Tidak bisa membuka layar izin: abaikan.
        }
    }

    private fun shotItemTapped(item: ShotItem, part: Int) {
        feedback(KeyType.CHARACTER)
        if (clipSelecting) {
            // Mode memilih: ketuk untuk memilih atau melepas pilihan.
            if (!shotSelected.add(item.id)) shotSelected.remove(item.id)
            if (shotSelected.isEmpty()) clipSelecting = false
            invalidate()
            return
        }
        when (part) {
            1 -> {
                ScreenshotStore.setPinned(context, setOf(item.id), !item.pinned)
                refreshShots()
            }
            2 -> {
                ScreenshotStore.dismiss(context, listOf(item))
                refreshShots()
            }
            else -> {
                // Ketuk gambar: tempel sebagai gambar, lalu kembali ke keyboard.
                listener?.onPasteImage(item.uri, item.mime)
                closeClipboard()
            }
        }
    }

    private fun drawClipCard(
        canvas: Canvas, item: ClipItem, index: Int,
        top: Float, cardH: Float, margin: Float, btnW: Float
    ) {
        val rect = RectF(margin, top, width - margin, top + cardH)
        val selected = item.id in clipSelected
        val pressed = panelTouchActive && clipDownIndex == index && clipDownPart == 0 && !panelScrolling
        fillPaint.color = if (selected || pressed) palette.keyPressed else palette.key
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        if (item.pinned) {
            // Disematkan: bingkai berwarna dan lencana pin di kiri. Item biasa tidak punya keduanya.
            strokePaint.color = PIN_COLOR
            strokePaint.strokeWidth = dp(2f)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }
        if (selected) {
            strokePaint.color = palette.text
            strokePaint.strokeWidth = dp(1.5f)
            canvas.drawRoundRect(rect.padded(-dp(4f)), radius, radius, strokePaint)
        }

        var textLeft = rect.left + dp(12f)
        if (item.pinned) {
            val bx = rect.left + dp(20f)
            fillPaint.color = PIN_COLOR
            canvas.drawCircle(bx, rect.centerY(), dp(11f), fillPaint)
            drawIconAt(canvas, bx, rect.centerY(), R.drawable.ic_kb_pin, dp(14f), tint = 0xFFFFFFFF.toInt())
            textLeft = rect.left + dp(40f)
        }
        val textRight = rect.right - if (clipSelecting) dp(44f) else 2 * btnW + dp(4f)
        val textWidth = (textRight - textLeft).toInt()
        if (textWidth > 0) {
            clipTextPaint.textSize = dp(14f)
            clipTextPaint.color = palette.text
            clipTextPaint.alpha = 255
            val layout = StaticLayout.Builder.obtain(item.preview, 0, item.preview.length, clipTextPaint, textWidth)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
            canvas.save()
            canvas.translate(textLeft, rect.centerY() - layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
        }

        if (clipSelecting) {
            // Kotak pilih di kanan: bulat kosong, atau bulat terisi dengan centang.
            val cx = rect.right - dp(22f)
            val cy = rect.centerY()
            val r = dp(10f)
            if (selected) {
                fillPaint.color = palette.text
                canvas.drawCircle(cx, cy, r, fillPaint)
                drawIconAt(canvas, cx, cy, R.drawable.ic_kb_check, r * 1.2f, tint = palette.key)
            } else {
                strokePaint.color = palette.text
                strokePaint.alpha = 140
                strokePaint.strokeWidth = dp(1.5f)
                canvas.drawCircle(cx, cy, r, strokePaint)
                strokePaint.alpha = 255
            }
        } else {
            // Tombol sematkan: ikon Pin bila belum disematkan, ikon Lepas sematan bila sudah. Lalu tombol hapus.
            val pinX = rect.right - btnW * 1.5f
            val delX = rect.right - btnW * 0.5f
            val pinHot = panelTouchActive && clipDownIndex == index && clipDownPart == 1 && !panelScrolling
            val delHot = panelTouchActive && clipDownIndex == index && clipDownPart == 2 && !panelScrolling
            if (item.pinned) {
                drawIconAt(canvas, pinX, rect.centerY(), R.drawable.ic_kb_unpin, dp(22f), 255)
            } else {
                drawIconAt(canvas, pinX, rect.centerY(), R.drawable.ic_kb_pin, dp(22f), if (pinHot) 255 else 150)
            }
            drawIconAt(canvas, delX, rect.centerY(), R.drawable.ic_kb_trash, dp(22f), if (delHot) 255 else 190)
        }
    }

    private data class IconKey(val res: Int, val size: Int, val tint: Int)

    private val iconBitmaps = HashMap<IconKey, Bitmap>()

    /**
     * Gambar ikon [res] berukuran [size] piksel yang berpusat di ([cx], [cy]).
     *
     * Ikon dirender ke bitmap lebih dulu (per ukuran dan warna) lalu digambar sebagai bitmap. Menggambar
     * satu objek Drawable yang sama berkali-kali dengan posisi atau warna berbeda dalam satu frame membuat
     * semua gambarnya memakai pengaturan terakhir di kanvas hardware, sehingga ikon bisa hilang atau
     * salah tempat (misalnya ikon pin).
     */
    private fun drawIconAt(
        canvas: Canvas, cx: Float, cy: Float, res: Int, size: Float,
        alpha: Int = 255, tint: Int = palette.text
    ) {
        val px = max(1, size.toInt())
        val key = IconKey(res, px, tint)
        var bitmap = iconBitmaps[key]
        if (bitmap == null) {
            val d = ContextCompat.getDrawable(context, res)?.mutate() ?: return
            if (iconBitmaps.size > 120) iconBitmaps.clear()
            bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            d.setTint(tint)
            d.setBounds(0, 0, px, px)
            d.draw(Canvas(bitmap))
            iconBitmaps[key] = bitmap
        }
        bitmapPaint.alpha = alpha
        canvas.drawBitmap(bitmap, cx - px / 2f, cy - px / 2f, bitmapPaint)
        bitmapPaint.alpha = 255
    }

    private fun handleClipboardTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> clipDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> if (panelTouchActive) clipMove(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                if (panelTouchActive) clipUp(event.x, event.y)
                panelTouchActive = false
            }
            MotionEvent.ACTION_CANCEL -> panelCancel()
        }
        return true
    }

    private fun clipDown(x: Float, y: Float) {
        val g = panelGeo()
        panelTouchActive = true
        panelDownY = y
        panelLastY = y
        panelScrolling = false
        panelPressedBottom = -1
        clipLongFired = false
        clipDownIndex = -1
        clipHeaderTarget = -1
        repeatHandler.removeCallbacks(clipLpRunnable)
        if (y < g.gridTop) {
            panelRegion = PANEL_TAB
            clipHeaderTarget = clipTopTargetAt(x, y, g)
        } else {
            panelRegion = PANEL_GRID
            if (clipMode == 1 && !shotReady) {
                shotPromptPressed = shotPromptRect(g).contains(x, y)
            } else {
                val index = if (clipMode == 0) clipItemAt(y, g) else shotIndexAt(x, y, g)
                if (index >= 0) {
                    clipDownIndex = index
                    clipDownPart = if (clipMode == 0) clipPartAt(x) else shotPartAt(x, y, index, g)
                    // Tahan lama (sepanjang Durasi tekan lama) untuk mulai memilih beberapa item.
                    if (!clipSelecting) repeatHandler.postDelayed(clipLpRunnable, longPressDelay)
                }
            }
        }
        invalidate()
    }

    private fun clipMove(x: Float, y: Float) {
        when (panelRegion) {
            PANEL_BOTTOM -> panelBottomMove(x)
            PANEL_GRID -> {
                if (!panelScrolling && abs(y - panelDownY) > touchSlop) {
                    panelScrolling = true
                    repeatHandler.removeCallbacks(clipLpRunnable)
                }
                if (panelScrolling) {
                    val g = panelGeo()
                    if (clipMode == 0) {
                        clipScroll = (clipScroll - (y - panelLastY)).coerceIn(0f, clipScrollMax(g))
                    } else {
                        shotScroll = (shotScroll - (y - panelLastY)).coerceIn(0f, shotScrollMax(g))
                    }
                    invalidate()
                }
                panelLastY = y
            }
        }
    }

    private fun clipLongPress() {
        if (!clipOpen || !panelTouchActive || panelRegion != PANEL_GRID || panelScrolling) return
        val id: Long? = if (clipMode == 0) {
            clipItems.getOrNull(clipDownIndex)?.id
        } else {
            shotItems.getOrNull(clipDownIndex)?.id
        }
        if (id == null) return
        clipLongFired = true
        if (vibrateOn) vibrate()
        clipSelecting = true
        clipSelected.clear()
        shotSelected.clear()
        if (clipMode == 0) clipSelected.add(id) else shotSelected.add(id)
        invalidate()
    }

    private fun clipUp(x: Float, y: Float) {
        repeatHandler.removeCallbacks(clipLpRunnable)
        val g = panelGeo()
        when (panelRegion) {
            PANEL_TAB -> {
                val target = clipHeaderTarget
                if (target >= 0 && clipTopTargetAt(x, y, g) == target) {
                    feedback(KeyType.CHARACTER)
                    clipTopAction(target)
                }
            }
            PANEL_GRID -> if (clipMode == 1 && !shotReady) {
                if (shotPromptPressed && shotPromptRect(g).contains(x, y)) {
                    feedback(KeyType.CHARACTER)
                    openScreenshotAccess()
                }
            } else if (!panelScrolling && !clipLongFired) {
                if (clipMode == 0) {
                    val item = clipItems.getOrNull(clipDownIndex)
                    if (item != null && clipItemAt(y, g) == clipDownIndex) clipItemTapped(item, clipDownPart)
                } else {
                    val item = shotItems.getOrNull(clipDownIndex)
                    if (item != null && shotIndexAt(x, y, g) == clipDownIndex) shotItemTapped(item, clipDownPart)
                }
            }
        }
        if (clipOpen) panelReset()
    }

    private fun clipItemTapped(item: ClipItem, part: Int) {
        feedback(KeyType.CHARACTER)
        if (clipSelecting) {
            // Mode memilih: ketuk kartu untuk memilih atau melepas pilihan.
            if (!clipSelected.add(item.id)) clipSelected.remove(item.id)
            if (clipSelected.isEmpty()) clipSelecting = false
            invalidate()
            return
        }
        when (part) {
            1 -> {
                ClipboardStore.setPinned(setOf(item.id), !item.pinned)
                refreshClipItems()
            }
            2 -> {
                ClipboardStore.delete(setOf(item.id))
                refreshClipItems()
            }
            else -> {
                // Ketuk kartu: tempel teksnya dan tetap berada di papan klip (tidak kembali ke qwerty).
                listener?.onKeyText(item.text)
            }
        }
    }

    /** Aksi tombol di judul saat memilih: 0 = sematkan atau lepas sematan, 1 = hapus, 2 = batal. */
    private fun clipHeaderAction(button: Int) {
        if (clipMode == 1) {
            val ids = HashSet(shotSelected)
            when (button) {
                0 -> {
                    val pin = shotItems.any { it.id in ids && !it.pinned }
                    ScreenshotStore.setPinned(context, ids, pin)
                }
                1 -> ScreenshotStore.dismiss(context, shotItems.filter { it.id in ids })
            }
            clipSelecting = false
            shotSelected.clear()
            refreshShots()
            return
        }
        val ids = HashSet(clipSelected)
        when (button) {
            0 -> {
                // Ada yang belum disematkan: sematkan semuanya. Semua sudah disematkan: lepas.
                val pin = clipItems.any { it.id in ids && !it.pinned }
                ClipboardStore.setPinned(ids, pin)
            }
            1 -> ClipboardStore.delete(ids)
        }
        clipSelecting = false
        clipSelected.clear()
        refreshClipItems()
    }

    private fun refreshClipItems() {
        clipItems = ClipboardStore.items()
        clipScroll = clipScroll.coerceIn(0f, clipScrollMax(panelGeo()))
        invalidate()
    }

    // ----------------------------------------------------------- Kontrol kursor

    private fun openEdit() {
        if (editOpen || !cursorToolOn) return
        closeResize()
        closeTranslate()
        closeEmoji()
        closeClipboard()
        cancelTouches()
        editSelecting = false
        panelTouchActive = false
        editOpen = true
        requestLayout()
        invalidate()
    }

    /** Tutup panel kontrol kursor dan kembali ke tombol. */
    fun closeEdit() {
        if (!editOpen) return
        editOpen = false
        editSelecting = false
        panelCancel()
        requestLayout()
        invalidate()
    }

    /** Ada teks yang diblok di kolom teks: tombol Pilih semua berubah menjadi Potong. */
    fun setHasSelection(value: Boolean) {
        if (hasSelection == value) return
        hasSelection = value
        if (editOpen) invalidate()
    }

    private class EditGeo(val left: Float, val top: Float, val cellW: Float, val cellH: Float, val gap: Float)

    private fun editGeo(g: PanelGeo): EditGeo {
        val margin = dp(8f)
        val gap = dp(6f)
        val top = g.gridTop
        val cellH = (g.gridBottom - top - 3 * gap) / 4f
        val cellW = (width - 2 * margin - 3 * gap) / 4f
        return EditGeo(margin, top, cellW, cellH, gap)
    }

    /** Kotak tombol: kolom [col], baris [row], melebar [cs] kolom dan [rs] baris. */
    private fun editSpan(eg: EditGeo, col: Int, row: Int, cs: Int, rs: Int): RectF {
        val left = eg.left + col * (eg.cellW + eg.gap)
        val top = eg.top + row * (eg.cellH + eg.gap)
        return RectF(
            left, top,
            left + cs * eg.cellW + (cs - 1) * eg.gap,
            top + rs * eg.cellH + (rs - 1) * eg.gap
        )
    }

    private fun editButtonRect(button: Int, eg: EditGeo): RectF = when (button) {
        EB_LEFT -> editSpan(eg, 0, 0, 1, 3)
        EB_UP -> editSpan(eg, 1, 0, 1, 1)
        EB_SELECT -> editSpan(eg, 1, 1, 1, 1)
        EB_DOWN -> editSpan(eg, 1, 2, 1, 1)
        EB_RIGHT -> editSpan(eg, 2, 0, 1, 3)
        EB_ALL -> editSpan(eg, 3, 0, 1, 1)
        EB_COPY -> editSpan(eg, 3, 1, 1, 1)
        EB_PASTE -> editSpan(eg, 3, 2, 1, 1)
        EB_DELETE -> editSpan(eg, 3, 3, 1, 1)
        else -> {
            // Baris bawah: Awal dan Akhir membagi dua lebar tiga kolom pertama.
            val full = editSpan(eg, 0, 3, 3, 1)
            val half = (full.width() - eg.gap) / 2f
            if (button == EB_HOME) {
                RectF(full.left, full.top, full.left + half, full.bottom)
            } else {
                RectF(full.right - half, full.top, full.right, full.bottom)
            }
        }
    }

    /** Salin hanya bisa bila ada teks yang diblok (Potong dan lainnya selalu aktif). */
    private fun editEnabled(button: Int): Boolean = button != EB_COPY || hasSelection

    private fun isRepeatButton(button: Int): Boolean = isArrowButton(button) || button == EB_DELETE

    private fun editButtonAt(x: Float, y: Float, g: PanelGeo): Int {
        val eg = editGeo(g)
        for (b in 0 until EDIT_BUTTON_COUNT) {
            if (editButtonRect(b, eg).contains(x, y)) return b
        }
        return -1
    }

    /** Aksi untuk tombol [button]; null untuk tombol Pilih (hanya mengubah mode). */
    private fun editActionFor(button: Int): EditAction? = when (button) {
        EB_HOME -> EditAction.HOME
        EB_UP -> EditAction.UP
        EB_END -> EditAction.END
        EB_LEFT -> EditAction.LEFT
        EB_RIGHT -> EditAction.RIGHT
        EB_DOWN -> EditAction.DOWN
        EB_ALL -> if (hasSelection) EditAction.CUT else EditAction.SELECT_ALL
        EB_COPY -> EditAction.COPY
        EB_PASTE -> EditAction.PASTE
        else -> null
    }

    private fun isArrowButton(button: Int): Boolean =
        button == EB_UP || button == EB_DOWN || button == EB_LEFT || button == EB_RIGHT

    /** Jalankan tombol [button]: Pilih mengubah mode, tombol lain mengirim aksinya ke keyboard. */
    private fun fireEdit(button: Int) {
        if (button == EB_SELECT) {
            editSelecting = !editSelecting
            invalidate()
            return
        }
        val action = editActionFor(button) ?: return
        listener?.onEditAction(action, editSelecting)
        // Salin, tempel, potong, dan pilih semua mengakhiri mode Pilih.
        if (!isArrowButton(button) && button != EB_HOME && button != EB_END) editSelecting = false
        invalidate()
    }

    private fun drawEditPanel(canvas: Canvas) {
        val g = panelGeo()
        val eg = editGeo(g)

        // Bilah atas: kembali dan nama.
        drawToolBarButton(
            canvas, toolBarBackRect(g), R.drawable.ic_kb_back, true,
            panelTouchActive && editTopTarget == EDIT_T_BACK
        )
        drawToolBarTitle(canvas, g, context.getString(R.string.edit_title))

        for (b in 0 until EDIT_BUTTON_COUNT) {
            val r = editButtonRect(b, eg)
            val enabled = editEnabled(b)
            val active = b == EB_SELECT && editSelecting
            if (active) {
                // Mode Pilih menyala: warna dibalik supaya jelas.
                fillPaint.color = palette.text
                canvas.drawRoundRect(r, radius, radius, fillPaint)
            } else {
                drawPanelFace(canvas, r, enabled && panelTouchActive && editPressedButton == b)
            }
            val fg = if (active) palette.key else palette.text
            val iconSize = minOf(r.width(), r.height()) * 0.42f
            val iconRes = when (b) {
                EB_HOME -> R.drawable.ic_kb_line_start
                EB_END -> R.drawable.ic_kb_line_end
                EB_UP -> R.drawable.ic_kb_arrow_up
                EB_DOWN -> R.drawable.ic_kb_arrow_down
                EB_LEFT -> R.drawable.ic_kb_arrow_left
                EB_RIGHT -> R.drawable.ic_kb_arrow_right
                EB_DELETE -> R.drawable.ic_kb_backspace
                else -> 0
            }
            if (iconRes != 0) {
                drawIconAt(canvas, r.centerX(), r.centerY(), iconRes, iconSize, tint = fg)
            } else {
                val label = when (b) {
                    EB_SELECT -> R.string.edit_select
                    EB_ALL -> if (hasSelection) R.string.edit_cut else R.string.edit_select_all
                    EB_COPY -> R.string.edit_copy
                    else -> R.string.edit_paste
                }
                // Tombol yang belum bisa dipakai (Salin tanpa blok teks) redup.
                val color = if (enabled) fg else (fg and 0x00FFFFFF) or (0x5A shl 24)
                drawFittedLabel(canvas, r, context.getString(label), color)
            }
        }
    }

    private fun editTopTargetAt(x: Float, y: Float, g: PanelGeo): Int {
        if (y >= g.tabsBottom + dp(2f)) return -1
        return if (toolBarBackRect(g).padded(dp(3f)).contains(x, y)) EDIT_T_BACK else -1
    }

    /** Teks di tengah [rect], dikecilkan bila terlalu lebar. */
    private fun drawFittedLabel(canvas: Canvas, rect: RectF, text: String, color: Int) {
        var size = rowHeight * 0.3f
        textPaint.textSize = size
        val limit = rect.width() * 0.86f
        while (textPaint.measureText(text) > limit && size > rowHeight * 0.14f) {
            size -= dp(0.5f)
            textPaint.textSize = size
        }
        textPaint.color = color
        val y = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, rect.centerX(), y, textPaint)
    }

    private fun handleEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> editDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> if (panelTouchActive) editMove(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                if (panelTouchActive) editUp(event.x, event.y)
                panelTouchActive = false
            }
            MotionEvent.ACTION_CANCEL -> panelCancel()
        }
        return true
    }

    private fun editDown(x: Float, y: Float) {
        val g = panelGeo()
        panelTouchActive = true
        panelPressedBottom = -1
        editDownButton = -1
        editPressedButton = -1
        editTopTarget = -1
        if (y < g.gridTop) {
            panelRegion = PANEL_TAB
            editTopTarget = editTopTargetAt(x, y, g)
        } else {
            panelRegion = PANEL_GRID
            val b = editButtonAt(x, y, g)
            if (b >= 0 && editEnabled(b)) {
                editDownButton = b
                editPressedButton = b
                if (b == EB_DELETE) {
                    // Hapus: langsung menghapus, dan berulang selama ditahan.
                    feedback(KeyType.DELETE)
                    listener?.onKeyDelete()
                    repeatHandler.removeCallbacks(repeatRunnable)
                    repeatHandler.postDelayed(repeatRunnable, longPressDelay)
                } else if (isArrowButton(b)) {
                    // Panah langsung bergerak, dan terus berulang selama ditahan.
                    feedback(KeyType.CHARACTER)
                    fireEdit(b)
                    repeatHandler.removeCallbacks(editRepeatRunnable)
                    repeatHandler.postDelayed(editRepeatRunnable, longPressDelay)
                }
            }
        }
        invalidate()
    }

    private fun editMove(x: Float, y: Float) {
        if (panelRegion != PANEL_GRID) return
        if (editDownButton >= 0 && editButtonAt(x, y, panelGeo()) != editDownButton) {
            // Jari keluar dari tombol: batal dan berhenti mengulang.
            repeatHandler.removeCallbacks(editRepeatRunnable)
            repeatHandler.removeCallbacks(repeatRunnable)
            editDownButton = -1
            editPressedButton = -1
            invalidate()
        }
    }

    private fun editUp(x: Float, y: Float) {
        val g = panelGeo()
        when (panelRegion) {
            PANEL_TAB -> {
                if (editTopTarget == EDIT_T_BACK && editTopTargetAt(x, y, g) == EDIT_T_BACK) {
                    feedback(KeyType.CHARACTER)
                    closeEdit()
                }
            }
            PANEL_GRID -> {
                repeatHandler.removeCallbacks(editRepeatRunnable)
                repeatHandler.removeCallbacks(repeatRunnable)
                val b = editDownButton
                // Tombol selain panah dan hapus bekerja saat dilepas di tombol yang sama.
                if (b >= 0 && !isRepeatButton(b) && editButtonAt(x, y, g) == b) {
                    feedback(KeyType.CHARACTER)
                    fireEdit(b)
                }
            }
        }
        if (editOpen) panelReset()
    }

    // ------------------------------------------------------------------ Ubah ukuran

    private fun openResize() {
        if (resizeOpen) return
        closeTranslate()
        closeEmoji()
        closeClipboard()
        closeEdit()
        cancelTouches()
        resizeMode = RESIZE_NONE
        resizeButtonDown = -1
        resizeOpen = true
        invalidate()
    }

    /** Tutup mode Ubah ukuran dan simpan tinggi dan posisi yang sedang tampil. */
    fun closeResize() {
        if (!resizeOpen) return
        resizeOpen = false
        resizeMode = RESIZE_NONE
        resizeButtonDown = -1
        prefs.heightPercent = (heightScale * 100f).roundToInt()
        prefs.keyboardOffsetDp = kbOffsetDp.roundToInt()
        requestLayout()
        invalidate()
    }

    private class ResizeGeo(
        val reset: RectF,
        val done: RectF,
        val moveCx: Float,
        val moveCy: Float,
        val moveR: Float,
        val captionBaseline: Float,
        val percentBaseline: Float
    )

    /** Letak tulisan dan tombol di tengah bagian keyboard yang terlihat (di atas ruang kosong di bawahnya). */
    private fun resizeGeo(): ResizeGeo {
        val cx = width / 2f
        val usable = height - dp(kbOffsetDp)
        val cy = usable / 2f
        val btnW = dp(96f)
        val btnH = dp(44f)
        val gap = dp(14f)
        val moveR = dp(28f)
        val rowY = cy + dp(30f)
        val reset = RectF(cx - moveR - gap - btnW, rowY - btnH / 2f, cx - moveR - gap, rowY + btnH / 2f)
        val done = RectF(cx + moveR + gap, rowY - btnH / 2f, cx + moveR + gap + btnW, rowY + btnH / 2f)
        return ResizeGeo(reset, done, cx, rowY, moveR, cy - dp(34f), cy - dp(8f))
    }

    private fun baseRowHeight(): Float {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return dp(if (landscape) 38f else 50f)
    }

    private fun maxOffsetDp(): Float {
        val d = resources.displayMetrics
        return minOf(Prefs.KB_OFFSET_MAX_DP.toFloat(), d.heightPixels / d.density * 0.2f)
    }

    /** Lapisan gelap di atas keyboard, lalu pegangan tinggi, persentase tinggi, dan tombol Reset, geser, Selesai. */
    private fun drawResizeOverlay(canvas: Canvas) {
        canvas.drawColor(0xB8000000.toInt())
        val white = 0xFFFFFFFF.toInt()
        val g = resizeGeo()
        val cx = width / 2f

        // Pegangan tinggi di tepi atas.
        fillPaint.color = (white and 0x00FFFFFF) or (0xE6 shl 24)
        canvas.drawRoundRect(RectF(cx - dp(32f), dp(10f), cx + dp(32f), dp(16f)), dp(3f), dp(3f), fillPaint)
        barTextPaint.color = white
        barTextPaint.textSize = dp(11f)
        barTextPaint.alpha = 170
        canvas.drawText(context.getString(R.string.resize_hint), cx, dp(32f), barTextPaint)

        // Nama dan persentase tinggi keyboard.
        barTextPaint.textSize = dp(12f)
        barTextPaint.alpha = 200
        canvas.drawText(context.getString(R.string.resize_caption), cx, g.captionBaseline, barTextPaint)
        barTextPaint.textSize = dp(24f)
        barTextPaint.alpha = 255
        barTextPaint.isFakeBoldText = true
        canvas.drawText(
            context.getString(R.string.height_fmt, (heightScale * 100f).roundToInt()),
            cx, g.percentBaseline, barTextPaint
        )
        barTextPaint.isFakeBoldText = false

        drawResizeButton(canvas, g.reset, context.getString(R.string.resize_reset), false, resizeButtonDown == 0)
        drawResizeButton(canvas, g.done, context.getString(R.string.btn_done), true, resizeButtonDown == 1)

        // Pegangan geser naik-turun: lingkaran dengan tanda perempatan.
        val moving = resizeMode == RESIZE_MOVE
        fillPaint.color = if (moving) 0x66FFFFFF else 0x33FFFFFF
        canvas.drawCircle(g.moveCx, g.moveCy, g.moveR, fillPaint)
        strokePaint.color = white
        strokePaint.strokeWidth = dp(1.5f)
        canvas.drawCircle(g.moveCx, g.moveCy, g.moveR, strokePaint)
        drawIconAt(canvas, g.moveCx, g.moveCy, R.drawable.ic_kb_move, g.moveR * 1.1f, 255, white)
    }

    private fun drawResizeButton(canvas: Canvas, r: RectF, label: String, filled: Boolean, pressed: Boolean) {
        val white = 0xFFFFFFFF.toInt()
        val corner = r.height() / 2f
        if (filled) {
            fillPaint.color = if (pressed) 0xFFDDDDDD.toInt() else white
            canvas.drawRoundRect(r, corner, corner, fillPaint)
        } else {
            if (pressed) {
                fillPaint.color = 0x33FFFFFF
                canvas.drawRoundRect(r, corner, corner, fillPaint)
            }
            strokePaint.color = white
            strokePaint.strokeWidth = dp(1.5f)
            canvas.drawRoundRect(r, corner, corner, strokePaint)
        }
        barTextPaint.textSize = dp(14f)
        barTextPaint.color = if (filled) 0xFF111111.toInt() else white
        barTextPaint.alpha = 255
        val y = r.centerY() - (barTextPaint.ascent() + barTextPaint.descent()) / 2f
        canvas.drawText(label, r.centerX(), y, barTextPaint)
    }

    private fun handleResizeTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> resizeDown(event.x, event.y, event.rawY)
            MotionEvent.ACTION_MOVE -> resizeMove(event.x, event.y, event.rawY)
            MotionEvent.ACTION_UP -> resizeUp(event.x, event.y)
            MotionEvent.ACTION_CANCEL -> {
                resizeMode = RESIZE_NONE
                resizeButtonDown = -1
                invalidate()
            }
        }
        return true
    }

    private fun resizeDown(x: Float, y: Float, rawY: Float) {
        val g = resizeGeo()
        resizeStartRawY = rawY
        resizeStartPercent = heightScale * 100f
        resizeStartOffset = kbOffsetDp
        resizeButtonDown = -1
        val dx = x - g.moveCx
        val dy = y - g.moveCy
        val reach = g.moveR + dp(10f)
        resizeMode = when {
            y < dp(44f) -> RESIZE_HEIGHT
            dx * dx + dy * dy <= reach * reach -> RESIZE_MOVE
            g.reset.contains(x, y) -> {
                resizeButtonDown = 0
                RESIZE_BUTTON
            }
            g.done.contains(x, y) -> {
                resizeButtonDown = 1
                RESIZE_BUTTON
            }
            else -> RESIZE_NONE
        }
        invalidate()
    }

    private fun resizeMove(x: Float, y: Float, rawY: Float) {
        when (resizeMode) {
            RESIZE_HEIGHT -> {
                // Tarik ke atas: keyboard makin tinggi. Memakai posisi layar (rawY) supaya tidak
                // ikut bergeser saat keyboard sendiri berubah ukuran.
                val keysAtFull = baseRowHeight() * rows.size
                val percent = (resizeStartPercent + (resizeStartRawY - rawY) / keysAtFull * 100f)
                    .coerceIn(Prefs.HEIGHT_MIN.toFloat(), Prefs.HEIGHT_MAX.toFloat())
                if (percent != heightScale * 100f) {
                    heightScale = percent / 100f
                    requestLayout()
                    invalidate()
                }
            }
            RESIZE_MOVE -> {
                // Tarik ke atas: keyboard naik. Tarik ke bawah: turun sampai menempel di tepi bawah.
                val delta = (resizeStartRawY - rawY) / resources.displayMetrics.density
                val offset = (resizeStartOffset + delta).coerceIn(0f, maxOffsetDp())
                if (offset != kbOffsetDp) {
                    kbOffsetDp = offset
                    requestLayout()
                    invalidate()
                }
            }
            RESIZE_BUTTON -> {
                val g = resizeGeo()
                val inside = if (resizeButtonDown == 0) g.reset.contains(x, y) else g.done.contains(x, y)
                if (!inside && resizeButtonDown >= 0) {
                    resizeButtonDown = -1
                    invalidate()
                }
            }
        }
    }

    private fun resizeUp(x: Float, y: Float) {
        val pressed = resizeButtonDown
        resizeMode = RESIZE_NONE
        resizeButtonDown = -1
        when (pressed) {
            0 -> {
                // Reset: tinggi 100% dan keyboard kembali menempel di bawah.
                feedback(KeyType.CHARACTER)
                heightScale = Prefs.HEIGHT_DEFAULT / 100f
                kbOffsetDp = 0f
                requestLayout()
            }
            1 -> {
                feedback(KeyType.CHARACTER)
                closeResize()
            }
        }
        invalidate()
    }

    // ----------------------------------------------------------------- Terjemahan

    /** Mode Terjemahan menyala: ketikan masuk ke kolom ketik di keyboard, bukan ke aplikasi. */
    val translateActive: Boolean
        get() = trOpen

    private fun trAreaHeight(): Float = 2 * trCardH + dp(12f)

    private fun openTranslate() {
        if (trOpen) return
        closeResize()
        closeEmoji()
        closeClipboard()
        closeEdit()
        cancelTouches()
        trFrom = prefs.translateFrom
        trTo = prefs.translateTo
        trInput.setLength(0)
        // Ada teks yang diblok di aplikasi: dijadikan bahan awal terjemahan.
        if (hasSelection) {
            trInput.append((listener?.onReadFieldText() ?: "").take(MAX_TRANSLATE_CHARS))
        }
        trState = TR_IDLE
        trResult = ""
        trMissingNames = ""
        trRequestId++
        trPicking = 0
        trPickScroll = 0f
        TranslationHelper.downloadedModels { set ->
            trDownloaded = set
            if (trOpen) invalidate()
        }
        trOpen = true
        requestLayout()
        invalidate()
    }

    /** Tutup mode Terjemahan: kolom ketik dikosongkan dan keyboard kembali seperti biasa. */
    fun closeTranslate() {
        if (!trOpen) return
        trOpen = false
        trPicking = 0
        trRequestId++
        trInput.setLength(0)
        trPointer = -1
        trTarget = -1
        trScrolling = false
        requestLayout()
        invalidate()
    }

    /** Huruf yang diketik di keyboard saat mode Terjemahan. */
    fun translateType(text: String) {
        if (!trOpen || trInput.length + text.length > MAX_TRANSLATE_CHARS) return
        trInput.append(text)
        trInputChanged()
    }

    /** Hapus satu karakter terakhir di kolom ketik. */
    fun translateBackspace() {
        if (!trOpen || trInput.isEmpty()) return
        trInput.setLength(trInput.length - Character.charCount(Character.codePointBefore(trInput, trInput.length)))
        trInputChanged()
    }

    /** Terjemahkan kalimat di kolom ketik (juga dipakai tombol Enter keyboard). */
    fun translateRun() {
        if (trOpen) trRun()
    }

    private fun trInputChanged() {
        // Teks berubah: hasil lama tidak berlaku lagi.
        if (trState != TR_IDLE) {
            trRequestId++
            trState = TR_IDLE
            trResult = ""
        }
        invalidate()
    }

    private class TrGeo(
        val result: RectF, val input: RectF, val translate: RectF, val insert: RectF, val downloads: RectF,
        val back: RectF, val from: RectF, val swap: RectF, val to: RectF
    )

    /**
     * Susunan dari atas: kartu hasil, kartu ketik (tombol terjemah di paling kanan), bilah bahasa
     * (kembali, bahasa asal, tukar, bahasa tujuan), lalu tombol qwerty.
     */
    private fun trGeo(): TrGeo {
        val margin = dp(8f)
        val gap = dp(4f)
        val result = RectF(margin, gap, width - margin, gap + trCardH)
        val input = RectF(margin, result.bottom + gap, width - margin, result.bottom + gap + trCardH)
        val button = trCardH - dp(8f)
        val translate = RectF(
            input.right - dp(4f) - button, input.centerY() - button / 2f,
            input.right - dp(4f), input.centerY() + button / 2f
        )
        val insert = RectF(
            result.right - dp(4f) - dp(92f), result.centerY() - button / 2f,
            result.right - dp(4f), result.centerY() + button / 2f
        )
        val downloads = RectF(
            result.right - dp(4f) - dp(132f), result.centerY() - button / 2f,
            result.right - dp(4f), result.centerY() + button / 2f
        )

        val barTop = trAreaHeight() + dp(2f)
        val barBottom = trAreaHeight() + barH - dp(2f)
        val size = barBottom - barTop
        val back = RectF(margin, barTop, margin + size, barBottom)
        val chipW = ((width - 2 * (back.right + dp(8f)) - size - 2 * gap) / 2f).coerceIn(dp(60f), dp(110f))
        val cx = width / 2f
        val swap = RectF(cx - size / 2f, barTop, cx + size / 2f, barBottom)
        val from = RectF(swap.left - gap - chipW, barTop, swap.left - gap, barBottom)
        val to = RectF(swap.right + gap, barTop, swap.right + gap + chipW, barBottom)
        return TrGeo(result, input, translate, insert, downloads, back, from, swap, to)
    }

    private fun drawTranslateArea(canvas: Canvas) {
        val t = trGeo()
        val pressedTarget = if (trPointer >= 0) trTarget else -1

        // Kartu hasil: terjemahan, atau petunjuk dan pesan sesuai keadaan.
        fillPaint.color = palette.key
        canvas.drawRoundRect(t.result, radius, radius, fillPaint)
        val resultRight = when (trState) {
            TR_DONE -> t.insert.left
            TR_MISSING -> t.downloads.left
            else -> t.result.right
        } - dp(8f)
        val box = RectF(t.result.left + dp(12f), t.result.top + dp(4f), resultRight, t.result.bottom - dp(4f))
        when (trState) {
            TR_DONE -> drawTextBlock(canvas, trResult, box, 15f, 255, false)
            TR_BUSY -> drawTextBlock(canvas, context.getString(R.string.tr_busy), box, 14f, 160, false)
            TR_EMPTY -> drawTextBlock(canvas, context.getString(R.string.tr_empty), box, 14f, 160, false)
            TR_ERROR -> drawTextBlock(canvas, context.getString(R.string.tr_error), box, 14f, 200, false)
            TR_MISSING -> drawTextBlock(
                canvas, context.getString(R.string.tr_missing_fmt, trMissingNames), box, 13f, 220, false
            )
            else -> drawTextBlock(canvas, context.getString(R.string.tr_hint_idle), box, 14f, 160, false)
        }
        if (trState == TR_DONE) {
            drawTranslateButton(canvas, t.insert, context.getString(R.string.tr_insert), true, pressedTarget == TR_T_INSERT)
        }
        if (trState == TR_MISSING) {
            drawTranslateButton(
                canvas, t.downloads, context.getString(R.string.tr_open_downloads), true,
                pressedTarget == TR_T_DOWNLOADS
            )
        }

        // Kartu ketik: kalimat yang diketik di keyboard, tombol terjemah (mirip enter) di paling kanan.
        fillPaint.color = palette.key
        canvas.drawRoundRect(t.input, radius, radius, fillPaint)
        val canRun = trInput.isNotBlank() && trState != TR_BUSY
        fillPaint.color = if (pressedTarget == TR_T_ENTER) palette.keyPressed else palette.text
        fillPaint.alpha = if (canRun) 255 else 80
        canvas.drawRoundRect(t.translate, radius, radius, fillPaint)
        fillPaint.alpha = 255
        drawIconAt(
            canvas, t.translate.centerX(), t.translate.centerY(), R.drawable.ic_kb_enter,
            t.translate.width() * 0.5f, if (canRun) 255 else 120, palette.key
        )
        drawInputText(canvas, RectF(t.input.left + dp(12f), t.input.top + dp(4f), t.translate.left - dp(8f), t.input.bottom - dp(4f)))

        // Bilah atas: kembali, bahasa asal, tukar, bahasa tujuan.
        drawToolBarButton(canvas, t.back, R.drawable.ic_kb_back, true, pressedTarget == TR_T_BACK)
        drawLanguageChip(canvas, t.from, TranslationHelper.languageName(trFrom), trPicking == 1, pressedTarget == TR_T_FROM)
        drawToolBarButton(canvas, t.swap, R.drawable.ic_kb_swap, true, pressedTarget == TR_T_SWAP)
        drawLanguageChip(canvas, t.to, TranslationHelper.languageName(trTo), trPicking == 2, pressedTarget == TR_T_TO)
    }

    /** Kalimat yang diketik: bagian paling akhir selalu terlihat, dengan garis kursor di ujungnya. */
    private fun drawInputText(canvas: Canvas, box: RectF) {
        if (box.width() < 4f || box.height() < 1f) return
        if (trInput.isEmpty()) {
            drawTextBlock(canvas, context.getString(R.string.tr_input_hint), box, 14f, 140, false)
            fillPaint.color = palette.text
            canvas.drawRect(box.left, box.centerY() - dp(9f), box.left + dp(1.5f), box.centerY() + dp(9f), fillPaint)
            return
        }
        clipTextPaint.textSize = dp(15f)
        clipTextPaint.color = palette.text
        clipTextPaint.alpha = 255
        val text = trInput.toString()
        val width = (box.width() - dp(4f)).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, clipTextPaint, width).build()
        canvas.save()
        canvas.clipRect(box)
        val dy = if (layout.height > box.height()) box.height() - layout.height else (box.height() - layout.height) / 2f
        canvas.translate(box.left, box.top + dy)
        layout.draw(canvas)
        val last = layout.lineCount - 1
        val caretX = layout.getLineRight(last) + dp(1f)
        fillPaint.color = palette.text
        canvas.drawRect(caretX, layout.getLineTop(last).toFloat(), caretX + dp(1.5f), layout.getLineBottom(last).toFloat(), fillPaint)
        canvas.restore()
    }

    /** Tombol bahasa di bilah atas. [active] = daftar bahasanya sedang terbuka (warna dibalik). */
    private fun drawLanguageChip(canvas: Canvas, rect: RectF, name: String, active: Boolean, pressed: Boolean) {
        if (active) {
            fillPaint.color = palette.text
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            drawFittedLabel(canvas, rect, name, palette.key)
        } else {
            drawPanelFace(canvas, rect, pressed)
            drawFittedLabel(canvas, rect, name, palette.text)
        }
    }

    private fun drawTranslateButton(canvas: Canvas, rect: RectF, label: String, enabled: Boolean, pressed: Boolean) {
        drawPanelFace(canvas, rect, enabled && pressed)
        val color = if (enabled) palette.text else (palette.text and 0x00FFFFFF) or (0x5A shl 24)
        drawFittedLabel(canvas, rect, label, color)
    }

    private fun trRowHeight(): Float = dp(48f)

    private fun trPickScrollMax(): Float =
        max(0f, TranslationHelper.languages().size * trRowHeight() - (height - bottomPad - topExtra))

    /** Daftar semua bahasa ML Kit: nama, status unduhan, dan tanda centang pada bahasa yang sedang dipilih. */
    private fun drawLanguagePicker(canvas: Canvas) {
        val margin = dp(8f)
        val top = topExtra
        val area = RectF(margin, top, width - margin, height - bottomPad)
        fillPaint.color = palette.key
        canvas.drawRoundRect(area, radius, radius, fillPaint)

        val langs = TranslationHelper.languages()
        val rowH = trRowHeight()
        val current = if (trPicking == 1) trFrom else trTo
        val first = (trPickScroll / rowH).toInt().coerceAtLeast(0)
        val last = minOf(langs.size - 1, ((trPickScroll + area.height()) / rowH).toInt())
        val pressedTarget = if (trPointer >= 0) trTarget else -1

        canvas.save()
        canvas.clipRect(area)
        for (i in first..last) {
            val code = langs[i]
            val rowTop = top + i * rowH - trPickScroll
            val row = RectF(area.left, rowTop, area.right, rowTop + rowH)
            val selected = code == current
            if (selected || pressedTarget == TR_T_ROW + i) {
                fillPaint.color = palette.keyPressed
                canvas.drawRect(row, fillPaint)
            }

            // Nama bahasa di kiri.
            headerPaint.textSize = dp(15f)
            headerPaint.color = palette.text
            headerPaint.alpha = 255
            val nameY = row.centerY() - (headerPaint.ascent() + headerPaint.descent()) / 2f
            canvas.drawText(TranslationHelper.languageName(code), row.left + dp(16f), nameY, headerPaint)

            // Status unduhan di kanan, di sebelah tanda centang.
            val status = context.getString(
                when {
                    code == TranslationHelper.ENGLISH -> R.string.dl_status_builtin
                    code in trDownloaded -> R.string.tr_downloaded
                    else -> R.string.tr_not_downloaded
                }
            )
            headerPaint.textSize = dp(12f)
            headerPaint.alpha = 150
            val right = row.right - dp(16f) - if (selected) dp(28f) else 0f
            val statusY = row.centerY() - (headerPaint.ascent() + headerPaint.descent()) / 2f
            canvas.drawText(status, right - headerPaint.measureText(status), statusY, headerPaint)
            headerPaint.alpha = 255

            if (selected) {
                drawIconAt(canvas, row.right - dp(24f), row.centerY(), R.drawable.ic_kb_check, dp(18f), 255, palette.text)
            }
            strokePaint.color = (palette.text and 0x00FFFFFF) or (0x26 shl 24)
            strokePaint.strokeWidth = dp(1f)
            canvas.drawLine(row.left + dp(16f), row.bottom, row.right - dp(16f), row.bottom, strokePaint)
        }
        canvas.restore()
    }

    /** Teks di dalam [box] dengan paling banyak baris yang muat; diakhiri "…" bila terpotong. */
    private fun drawTextBlock(canvas: Canvas, text: String, box: RectF, sizeDp: Float, alpha: Int, center: Boolean) {
        if (text.isEmpty() || box.width() < 1f || box.height() < 1f) return
        clipTextPaint.textSize = dp(sizeDp)
        clipTextPaint.color = palette.text
        clipTextPaint.alpha = alpha
        val metrics = clipTextPaint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val maxLines = max(1, (box.height() / lineHeight).toInt())
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, clipTextPaint, box.width().toInt())
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(
                if (center) android.text.Layout.Alignment.ALIGN_CENTER
                else android.text.Layout.Alignment.ALIGN_NORMAL
            )
            .build()
        canvas.save()
        canvas.clipRect(box)
        // Rata tengah vertikal agar satu baris tidak menempel ke atas kartu.
        canvas.translate(box.left, box.top + max(0f, (box.height() - layout.height) / 2f))
        layout.draw(canvas)
        canvas.restore()
        clipTextPaint.alpha = 255
    }

    /** Sasaran ketuk di titik ([x], [y]), atau -1. Tombol yang sedang tidak berlaku tidak dihitung. */
    private fun trTargetAt(x: Float, y: Float): Int {
        val t = trGeo()
        val pad = dp(3f)
        if (t.back.padded(pad).contains(x, y)) return TR_T_BACK
        if (t.swap.padded(pad).contains(x, y)) return TR_T_SWAP
        if (t.from.padded(pad).contains(x, y)) return TR_T_FROM
        if (t.to.padded(pad).contains(x, y)) return TR_T_TO
        if (trPicking != 0) {
            // Daftar bahasa: nomor baris di titik sentuh.
            if (y < topExtra || y > height - bottomPad) return -1
            val index = ((y - topExtra + trPickScroll) / trRowHeight()).toInt()
            return if (index in TranslationHelper.languages().indices) TR_T_ROW + index else -1
        }
        return when {
            trInput.isNotBlank() && trState != TR_BUSY && t.translate.padded(pad).contains(x, y) -> TR_T_ENTER
            trState == TR_DONE && t.insert.padded(pad).contains(x, y) -> TR_T_INSERT
            trState == TR_MISSING && t.downloads.padded(pad).contains(x, y) -> TR_T_DOWNLOADS
            else -> -1
        }
    }

    private fun trDown(id: Int, x: Float, y: Float) {
        if (trPointer >= 0) return
        trPointer = id
        trDownY = y
        trLastX = x
        trLastY = y
        trScrolling = false
        trTarget = trTargetAt(x, y)
        invalidate()
    }

    private fun trMove(x: Float, y: Float) {
        trLastX = x
        if (trPicking != 0 && (trScrolling || trDownY >= topExtra)) {
            // Menggulir daftar bahasa.
            if (!trScrolling && abs(y - trDownY) > touchSlop) {
                trScrolling = true
                trTarget = -1
            }
            if (trScrolling) {
                trPickScroll = (trPickScroll - (y - trLastY)).coerceIn(0f, trPickScrollMax())
                invalidate()
            }
            trLastY = y
            return
        }
        trLastY = y
        if (trTargetAt(x, y) != trTarget) {
            // Jari keluar dari tombol: batal.
            trTarget = -1
            invalidate()
        }
    }

    private fun trUp() {
        val target = trTarget
        val scrolled = trScrolling
        trPointer = -1
        trTarget = -1
        trScrolling = false
        if (!scrolled && target >= 0 && trTargetAt(trLastX, trLastY) == target) {
            feedback(KeyType.CHARACTER)
            trAction(target)
        }
        invalidate()
    }

    private fun trAction(target: Int) {
        when (target) {
            // Kembali: menutup daftar bahasa dulu bila terbuka, baru menutup mode Terjemahan.
            TR_T_BACK -> if (trPicking != 0) trPicking = 0 else closeTranslate()
            TR_T_SWAP -> {
                trPicking = 0
                trSwap()
            }
            TR_T_FROM -> trTogglePicker(1)
            TR_T_TO -> trTogglePicker(2)
            TR_T_ENTER -> trRun()
            TR_T_INSERT -> {
                listener?.onInsertText(trResult)
                closeTranslate()
            }
            TR_T_DOWNLOADS -> openDownloadsPage()
            else -> if (target >= TR_T_ROW) trChoose(target - TR_T_ROW)
        }
    }

    /** Buka daftar bahasa untuk [side] (1 = asal, 2 = tujuan), atau tutup bila sudah terbuka untuk sisi itu. */
    private fun trTogglePicker(side: Int) {
        if (trPicking == side) {
            trPicking = 0
            return
        }
        trPicking = side
        // Gulir supaya bahasa yang sedang dipilih terlihat, dan segarkan status unduhan.
        val index = TranslationHelper.languages().indexOf(if (side == 1) trFrom else trTo).coerceAtLeast(0)
        trPickScroll = (index * trRowHeight() - trRowHeight() * 2).coerceIn(0f, trPickScrollMax())
        TranslationHelper.downloadedModels { set ->
            trDownloaded = set
            if (trOpen) invalidate()
        }
    }

    /** Pilih bahasa nomor [index]. Bila sama dengan bahasa di sisi lain, keduanya saling bertukar. */
    private fun trChoose(index: Int) {
        val code = TranslationHelper.languages().getOrNull(index) ?: return
        if (trPicking == 1) {
            val oldFrom = trFrom
            trFrom = code
            if (code == trTo) trTo = oldFrom
        } else {
            val oldTo = trTo
            trTo = code
            if (code == trFrom) trFrom = oldTo
        }
        prefs.translateFrom = trFrom
        prefs.translateTo = trTo
        trPicking = 0
        trRequestId++
        trResult = ""
        trState = TR_IDLE
        invalidate()
    }

    /** Tukar bahasa asal dan tujuan, simpan, lalu kosongkan hasil lama. */
    private fun trSwap() {
        val old = trFrom
        trFrom = trTo
        trTo = old
        prefs.translateFrom = trFrom
        prefs.translateTo = trTo
        trRequestId++
        trResult = ""
        trState = TR_IDLE
        invalidate()
    }

    private fun trRun() {
        val text = trInput.toString().trim()
        if (text.isEmpty()) {
            trState = TR_EMPTY
            invalidate()
            return
        }
        if (trFrom == trTo) {
            // Bahasa asal dan tujuan sama: tidak ada yang perlu diterjemahkan.
            trResult = text
            trState = TR_DONE
            invalidate()
            return
        }
        trState = TR_BUSY
        trResult = ""
        val id = ++trRequestId
        invalidate()
        TranslationHelper.translate(text, trFrom, trTo) { res ->
            if (!trOpen || id != trRequestId) return@translate
            when (res.status) {
                TranslationHelper.STATUS_OK -> {
                    trResult = res.text
                    trState = TR_DONE
                }
                TranslationHelper.STATUS_MISSING -> {
                    trMissingNames = res.missing.joinToString(", ") { TranslationHelper.languageName(it) }
                    trState = TR_MISSING
                }
                else -> trState = TR_ERROR
            }
            invalidate()
        }
    }

    /** Buka halaman Paket Unduhan untuk mengunduh data bahasa yang kurang. */
    private fun openDownloadsPage() {
        closeTranslate()
        val intent = Intent(context, PreferencesActivity::class.java)
            .putExtra(PreferencesActivity.EXTRA_PAGE, PreferencesActivity.PAGE_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Tidak bisa membuka halaman: abaikan.
        }
    }

    // ------------------------------------------------------------ Gestur spasi

    private fun moveSpace(x: Float) {
        val dx = x - spaceStartX
        if (!spaceActive) {
            if (abs(dx) < dp(SPACE_ACTIVATE_DP)) return
            spaceActive = true
        }
        val steps = (dx / dp(SPACE_STEP_DP)).toInt()
        val delta = steps - spaceApplied
        if (delta != 0) {
            spaceApplied = steps
            listener?.onCursorMove(delta)
        }
    }

    private fun resetSpaceGesture() {
        spaceId = -1
        spaceStartX = 0f
        spaceApplied = 0
        spaceActive = false
    }

    // ------------------------------------------------------ Gestur Shift + hapus

    /** ID jari yang sedang menahan tombol Shift, atau -1 bila tidak ada. */
    private fun findHeldShiftId(): Int {
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).key.type == KeyType.SHIFT) return pointers.keyAt(i)
        }
        return -1
    }

    /** Mulai gestur seleksi bila syaratnya terpenuhi. True = sentuhan ini dipakai gestur. */
    private fun startSelectGesture(id: Int, x: Float): Boolean {
        if (!gestureSelectOn || currentLayout != LayoutMode.LETTERS) return false
        // Sudah ada gestur berjalan: jari hapus tambahan diabaikan, bukan dihapus biasa.
        if (selDeleteId >= 0) return true
        val shiftId = findHeldShiftId()
        if (shiftId < 0) return false

        repeatHandler.removeCallbacks(repeatRunnable)
        selDeleteId = id
        selShiftId = shiftId
        selStartX = x
        selLast = 0
        selActive = listener?.onSelectBegin() ?: false
        return true
    }

    private fun moveSelect(x: Float) {
        if (!selActive) return
        val chars = ((x - selStartX) / dp(SELECT_STEP_DP)).toInt()
        if (chars != selLast) {
            selLast = chars
            listener?.onSelectChange(chars)
        }
    }

    private fun cancelSelection() {
        if (selActive) {
            selActive = false
            listener?.onSelectCancel()
        }
        selLast = 0
    }

    // ------------------------------------------------------------------- Aksi

    private fun perform(key: Key) {
        when (key.type) {
            KeyType.CHARACTER -> commitCharacter(key)
            KeyType.SPACE -> listener?.onKeyText(" ")
            KeyType.SHIFT -> toggleShift()
            KeyType.ENTER -> listener?.onKeyEnter()
            KeyType.TO_SYMBOLS -> showLayout(LayoutMode.SYMBOLS_1)
            KeyType.TO_SYMBOLS_2 -> showLayout(LayoutMode.SYMBOLS_2)
            KeyType.TO_LETTERS -> showLayout(LayoutMode.LETTERS)
            else -> Unit
        }
    }

    private fun commitCharacter(key: Key) {
        val text = displayLabel(key)
        if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
        listener?.onKeyText(text)
    }

    /** Sekali ketuk = huruf besar sekali, dua kali ketuk cepat = caps lock. */
    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        shiftState = when (shiftState) {
            ShiftState.LOCK -> ShiftState.OFF
            ShiftState.ONCE ->
                if (now - lastShiftTap < DOUBLE_TAP_MS) ShiftState.LOCK else ShiftState.OFF
            ShiftState.OFF -> ShiftState.ONCE
        }
        lastShiftTap = now
    }

    // --------------------------------------------------------------- Umpan balik

    private fun feedback(type: KeyType) {
        if (vibrateOn) vibrate()
        if (soundOn) playSound(type)
    }

    private fun vibrate() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(vibrateMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun playSound(type: KeyType) {
        val effect = when (type) {
            KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        audioManager?.playSoundEffect(effect, soundGain)
    }

    /**
     * Muat AlteHaasGroteskBold dari assets/fonts. Kalau file font belum ada,
     * pakai bold bawaan sistem supaya keyboard tetap jalan.
     */
    private fun loadKeyTypeface(context: Context): Typeface =
        try {
            Typeface.createFromAsset(context.assets, FONT_PATH)
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }

    @Suppress("DEPRECATION")
    private fun createVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private companion object {
        const val REPEAT_INTERVAL_MS = 45L
        const val DOUBLE_TAP_MS = 350L

        // Panel emoji.
        const val PANEL_MIN_ROWS = 5
        const val EMOJI_MAX_RECENTS = 24
        const val EMOJI_HINT = "☺"
        const val EMOJI_RECENT_ICON = "🕘"
        const val PANEL_NONE = 0
        const val PANEL_TAB = 1
        const val PANEL_BOTTOM = 2
        const val PANEL_GRID = 3

        // Tombol di panel kontrol kursor, tata letak seperti Gboard (kisi 4 kolom x 4 baris):
        // kiri dan kanan tinggi (3 baris), tengah atas/pilih/bawah, kolom aksi, lalu baris bawah.
        const val EB_HOME = 0
        const val EB_UP = 1
        const val EB_END = 2
        const val EB_LEFT = 3
        const val EB_SELECT = 4
        const val EB_RIGHT = 5
        const val EB_DOWN = 6
        const val EB_ALL = 7
        const val EB_COPY = 8
        const val EB_PASTE = 9
        const val EB_DELETE = 10
        const val EDIT_BUTTON_COUNT = 11

        // Panel Terjemahan: keadaan hasil dan sasaran ketuk.
        const val TR_IDLE = 0
        const val TR_EMPTY = 1
        const val TR_BUSY = 2
        const val TR_DONE = 3
        const val TR_MISSING = 4
        const val TR_ERROR = 5
        const val TR_T_BACK = 1
        const val TR_T_SWAP = 2
        const val TR_T_ENTER = 3
        const val TR_T_REPLACE = 4
        const val TR_T_INSERT = 5
        const val TR_T_DOWNLOADS = 6
        const val TR_T_FROM = 7
        const val TR_T_TO = 8
        const val TR_T_ROW = 100 // 100 + nomor bahasa di daftar pemilih
        const val MAX_TRANSLATE_CHARS = 2000

        // Mode Ubah ukuran: apa yang sedang ditarik.
        const val RESIZE_NONE = 0
        const val RESIZE_HEIGHT = 1
        const val RESIZE_MOVE = 2
        const val RESIZE_BUTTON = 3

        // Penanda sasaran ketuk di bilah atas Tools: tombol kembali. Aksi memakai 0 (hapus), 1 (sematkan), 2 (tambah).
        const val CLIP_T_BACK = 50
        const val EDIT_T_BACK = 50

        // Warna penanda item yang disematkan (bingkai dan lencana), sama di tema terang dan gelap.
        val PIN_COLOR = 0xFFE8A317.toInt()

        // Penanda sasaran ketuk di judul papan klip: tombol mode Teks (10) dan Screenshot (11).
        const val CLIP_TARGET_MODE = 10
        const val FONT_PATH = "fonts/AlteHaasGroteskBold.ttf"

        // Gestur: jarak geser (dp) sebelum dianggap geseran, dan jarak per karakter.
        const val SPACE_ACTIVATE_DP = 16f
        const val SPACE_STEP_DP = 12f
        const val SELECT_STEP_DP = 10f

        // Jumlah kotak penanda di bilah Tools keyboard (hanya tampilan sementara).
        const val TOOLS_PLACEHOLDER_COUNT = 6

        // Kolom saran: tiga kotak. Urutan kotak kiri, tengah, kanan memakai saran ke-2, ke-1, ke-3.
        const val SUGGESTION_COUNT = 3
        val SLOT_ORDER = intArrayOf(1, 0, 2)
    }
}
