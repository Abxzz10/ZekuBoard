package com.zeku.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
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

enum class ShiftState { OFF, ONCE, LOCK }

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
        numberRowOn = prefs.numberRow
        commaOn = prefs.commaKey
        periodOn = prefs.periodKey
        toolsOn = prefs.toolsBar
        suggestOn = prefs.suggestionBar
        heightScale = prefs.heightPercent.coerceIn(80, 120) / 100f
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
        bottomPad = dp(6f)
        sidePad = dp(if (landscape) 16f else 4f)
        hInset = dp(3f)
        vInset = dp(if (landscape) 3f else 4f)
        radius = dp(10f)

        // Tinggi bilah atas: satu bilah saja, berapa pun yang menyala (Tools, saran, atau keduanya
        // bergantian). Keduanya mati: tidak ada ruang. Area ini bukan area tombol: sentuhan di sini
        // diabaikan (lihat findBox dan buildBoxes), jadi tidak ada tombol yang ikut terketik.
        barH = if (toolsOn || suggestOn) dp(if (landscape) 32f else 40f) else 0f
        topExtra = barH
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateMetrics()
        // Panel emoji butuh paling sedikit 5 baris tinggi supaya kisinya cukup lega.
        val rowCount = if (emojiOpen || clipOpen) max(rows.size, PANEL_MIN_ROWS) else rows.size
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
        drawTopBars(canvas)
        for (box in boxes) drawKey(canvas, box)
        previewBox?.let { drawPreview(canvas, it) }
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
            if (i == 0) {
                fillPaint.color = if (toolPressed) palette.keyPressed else palette.key
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                drawIconAt(canvas, rect.centerX(), rect.centerY(), R.drawable.ic_kb_clipboard, rect.width() * 0.56f)
            } else {
                fillPaint.color = (palette.key and 0x00FFFFFF) or (0x66 shl 24)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
        }
    }

    /** Nomor kotak alat di titik sentuh (hanya yang bisa diketuk), atau -1. */
    private fun toolSlotAt(x: Float, y: Float): Int {
        if (barH <= 0f || activeTopBar() != TopBarMode.TOOLS || y < 0f || y >= barH) return -1
        val rect = toolSlotRect(0, 0f, barH) ?: return -1
        val gap = dp(10f)
        return if (x >= rect.left - gap / 2f && x <= rect.right + gap / 2f) 0 else -1
    }

    private fun openTool(slot: Int) {
        when (slot) {
            0 -> openClipboard()
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
        if (emojiOpen) return handleEmojiTouch(event)
        if (clipOpen) return handleClipboardTouch(event)
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
        closeClipboard()
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
        clipDownIndex = -1
        clipHeaderTarget = -1
        invalidate()
    }

    private fun panelCancel() {
        repeatHandler.removeCallbacks(repeatRunnable)
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
        closeEmoji()
        cancelTouches()
        clipItems = ClipboardStore.items()
        clipScroll = 0f
        clipSelecting = false
        clipSelected.clear()
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

    /** Tombol di judul saat memilih: 0 = sematkan, 1 = hapus, 2 = batal. Dihitung dari kanan. */
    private fun clipHeaderRect(index: Int, g: PanelGeo): RectF {
        val size = g.tabsBottom - g.tabsTop
        val gap = dp(6f)
        val right = width - clipMargin() - (2 - index) * (size + gap)
        return RectF(right - size, g.tabsTop, right, g.tabsBottom)
    }

    private fun clipHeaderButtonAt(x: Float, g: PanelGeo): Int {
        for (i in 0..2) {
            val r = clipHeaderRect(i, g)
            if (x >= r.left - dp(3f) && x <= r.right + dp(3f)) return i
        }
        return -1
    }

    private fun drawClipboardPanel(canvas: Canvas) {
        val g = panelGeo()
        val margin = clipMargin()

        // Judul, atau jumlah yang dipilih beserta tombol sematkan, hapus, batal.
        headerPaint.textSize = (g.tabsBottom - g.tabsTop) * 0.46f
        headerPaint.color = palette.text
        val titleY = (g.tabsTop + g.tabsBottom) / 2f - (headerPaint.ascent() + headerPaint.descent()) / 2f
        val title = if (clipSelecting) {
            context.getString(R.string.clip_selected_fmt, clipSelected.size)
        } else {
            context.getString(R.string.clip_title)
        }
        canvas.drawText(title, margin + dp(4f), titleY, headerPaint)
        if (clipSelecting) {
            val icons = intArrayOf(R.drawable.ic_kb_pin, R.drawable.ic_kb_trash, R.drawable.ic_kb_close)
            for (i in 0..2) {
                val r = clipHeaderRect(i, g)
                drawPanelFace(canvas, r, clipHeaderTarget == i && panelTouchActive)
                drawIconAt(canvas, r.centerX(), r.centerY(), icons[i], r.width() * 0.5f)
            }
        }

        // Daftar kartu (bisa digulir).
        canvas.save()
        canvas.clipRect(0f, g.gridTop, width.toFloat(), g.gridBottom)
        if (clipItems.isEmpty()) {
            clipTextPaint.textSize = dp(14f)
            clipTextPaint.color = palette.text
            clipTextPaint.alpha = 150
            val w = (width - 2 * dp(32f)).toInt()
            val text = context.getString(R.string.clip_empty)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, clipTextPaint, w)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .build()
            canvas.translate(dp(32f), (g.gridTop + g.gridBottom) / 2f - layout.height / 2f)
            layout.draw(canvas)
        } else {
            val cardH = clipCardHeight()
            val btnW = clipButtonWidth()
            for (i in clipItems.indices) {
                val top = clipItemTop(i, g)
                if (top + cardH < g.gridTop || top > g.gridBottom) continue
                drawClipCard(canvas, clipItems[i], i, top, cardH, margin, btnW)
            }
        }
        canvas.restore()

        drawPanelBottomRow(canvas, g)
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
        if (selected) {
            strokePaint.color = palette.text
            strokePaint.strokeWidth = dp(1.5f)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }

        // Tanda sematan di kiri teks.
        var textLeft = rect.left + dp(12f)
        if (item.pinned) {
            drawIconAt(canvas, textLeft + dp(7f), rect.centerY(), R.drawable.ic_kb_pin, dp(14f))
            textLeft += dp(20f)
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
                val d = icon(R.drawable.ic_kb_check)
                if (d != null) {
                    d.setTint(palette.key)
                    val half = (r * 0.7f).toInt()
                    d.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
                    d.draw(canvas)
                }
            } else {
                strokePaint.color = palette.text
                strokePaint.alpha = 140
                strokePaint.strokeWidth = dp(1.5f)
                canvas.drawCircle(cx, cy, r, strokePaint)
                strokePaint.alpha = 255
            }
        } else {
            // Tombol sematkan (terang bila sedang disematkan) dan tombol hapus.
            val pinX = rect.right - btnW * 1.5f
            val delX = rect.right - btnW * 0.5f
            val pinHot = panelTouchActive && clipDownIndex == index && clipDownPart == 1 && !panelScrolling
            val delHot = panelTouchActive && clipDownIndex == index && clipDownPart == 2 && !panelScrolling
            drawIconAt(canvas, pinX, rect.centerY(), R.drawable.ic_kb_pin, dp(20f), if (item.pinned || pinHot) 255 else 110)
            drawIconAt(canvas, delX, rect.centerY(), R.drawable.ic_kb_trash, dp(20f), if (delHot) 255 else 170)
        }
    }

    /** Ikon berukuran [size] piksel yang berpusat di ([cx], [cy]), dengan warna teks keyboard. */
    private fun drawIconAt(canvas: Canvas, cx: Float, cy: Float, res: Int, size: Float, alpha: Int = 255) {
        val d = icon(res) ?: return
        val half = (size / 2f).toInt()
        d.setTint(palette.text)
        d.alpha = alpha
        d.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        d.draw(canvas)
        d.alpha = 255
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
        when {
            y >= g.gridBottom -> {
                panelRegion = PANEL_BOTTOM
                panelBottomDown(x)
            }
            y < g.gridTop -> {
                panelRegion = PANEL_TAB
                if (clipSelecting) clipHeaderTarget = clipHeaderButtonAt(x, g)
            }
            else -> {
                panelRegion = PANEL_GRID
                val index = clipItemAt(y, g)
                if (index >= 0) {
                    clipDownIndex = index
                    clipDownPart = clipPartAt(x)
                    // Tahan lama (sepanjang Durasi tekan lama) untuk mulai memilih beberapa teks.
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
                    clipScroll = (clipScroll - (y - panelLastY)).coerceIn(0f, clipScrollMax(g))
                    invalidate()
                }
                panelLastY = y
            }
        }
    }

    private fun clipLongPress() {
        if (!clipOpen || !panelTouchActive || panelRegion != PANEL_GRID || panelScrolling) return
        val item = clipItems.getOrNull(clipDownIndex) ?: return
        clipLongFired = true
        if (vibrateOn) vibrate()
        clipSelecting = true
        clipSelected.clear()
        clipSelected.add(item.id)
        invalidate()
    }

    private fun clipUp(x: Float, y: Float) {
        repeatHandler.removeCallbacks(clipLpRunnable)
        val g = panelGeo()
        when (panelRegion) {
            PANEL_TAB -> {
                if (clipSelecting && clipHeaderTarget >= 0 && clipHeaderButtonAt(x, g) == clipHeaderTarget) {
                    feedback(KeyType.CHARACTER)
                    clipHeaderAction(clipHeaderTarget)
                }
            }
            PANEL_BOTTOM -> panelBottomUp(x) { closeClipboard() }
            PANEL_GRID -> {
                val item = clipItems.getOrNull(clipDownIndex)
                if (item != null && !panelScrolling && !clipLongFired && clipItemAt(y, g) == clipDownIndex) {
                    clipItemTapped(item, clipDownPart)
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
                // Ketuk kartu: tempel teksnya, lalu kembali ke keyboard.
                listener?.onKeyText(item.text)
                closeClipboard()
            }
        }
    }

    /** Aksi tombol di judul saat memilih: 0 = sematkan atau lepas sematan, 1 = hapus, 2 = batal. */
    private fun clipHeaderAction(button: Int) {
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
