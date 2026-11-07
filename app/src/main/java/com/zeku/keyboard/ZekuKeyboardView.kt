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
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

enum class ShiftState { OFF, ONCE, LOCK }

/** Jenis aksi pada tombol enter, menentukan ikonnya. */
enum class EnterKind { ENTER, SEARCH, SEND, NEXT, DONE }

private class KeyBox(val key: Key, val cell: RectF, val face: RectF)

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
 * hapus berulang, pratinjau huruf, getar, dan suara.
 */
class ZekuKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKeyText(text: String)
        fun onKeyDelete()
        fun onKeyEnter()
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

    private var rowHeight = 0f
    private var topPad = 0f
    private var bottomPad = 0f
    private var sidePad = 0f
    private var hInset = 0f
    private var vInset = 0f
    private var radius = 0f

    private val boxes = ArrayList<KeyBox>()
    private val pointers = SparseArray<KeyBox>()
    private var previewBox: KeyBox? = null
    private var previewPointer = -1
    private var lastShiftTap = 0L

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    // Font hanya untuk tulisan di tombol; teks yang diketik ke aplikasi tidak terpengaruh.
    private val keyTypeface: Typeface = loadKeyTypeface(context)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = keyTypeface
    }
    private val icons = HashMap<Int, Drawable?>()

    private val repeatHandler = Handler(Looper.getMainLooper())
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

    /** Baca ulang pengaturan (tema, tinggi, getar, suara) dari [Prefs]. */
    fun applyPrefs() {
        vibrateOn = prefs.vibrate
        soundOn = prefs.sound
        heightScale = prefs.heightPercent.coerceIn(80, 120) / 100f
        isDarkTheme = when (prefs.theme) {
            Prefs.THEME_DARK -> true
            Prefs.THEME_LIGHT -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        palette = if (isDarkTheme) Palette.DARK else Palette.LIGHT
        updateMetrics()
        requestLayout()
        buildBoxes()
        invalidate()
    }

    fun showLayout(mode: LayoutMode) {
        if (mode == currentLayout) return
        currentLayout = mode
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

    // ----------------------------------------------------------- Ukuran/tata letak

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun updateMetrics() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        rowHeight = dp(if (landscape) 38f else 50f) * heightScale
        topPad = dp(8f)
        bottomPad = dp(6f)
        sidePad = dp(if (landscape) 16f else 4f)
        hInset = dp(3f)
        vInset = dp(if (landscape) 3f else 4f)
        radius = dp(10f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateMetrics()
        val rows = KeyboardLayouts.get(currentLayout).size
        val h = (rowHeight * rows + topPad + bottomPad).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildBoxes()
    }

    private fun buildBoxes() {
        cancelTouches()
        boxes.clear()
        if (width <= 0 || height <= 0) return

        val rows = KeyboardLayouts.get(currentLayout)
        val rowH = (height - topPad - bottomPad) / rows.size

        rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (width - 2 * sidePad) / total
            val top = topPad + r * rowH
            val bottom = topPad + (r + 1) * rowH
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
                if (r == 0) rowBoxes.forEach { it.cell.top = 0f }
                if (r == rows.size - 1) rowBoxes.forEach { it.cell.bottom = height.toFloat() }
            }
            boxes.addAll(rowBoxes)
        }
    }

    // ----------------------------------------------------------------- Gambar

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        for (box in boxes) drawKey(canvas, box)
        previewBox?.let { drawPreview(canvas, it) }
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
            KeyType.CHARACTER -> drawLabel(canvas, face, displayLabel(key), rowHeight * 0.42f)
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
        val left = (face.centerX() - pw / 2f).coerceIn(dp(2f), width - pw - dp(2f))
        val bottom = face.top - dp(2f)
        val rect = RectF(left, bottom - rowHeight, left + pw, bottom)

        fillPaint.color = palette.popup
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        strokePaint.color = palette.popupStroke
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        drawLabel(canvas, rect, displayLabel(box.key), rowHeight * 0.6f)
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
        val px = x.coerceIn(0f, width - 1f)
        val py = y.coerceIn(0f, height - 1f)
        for (box in boxes) {
            if (box.cell.contains(px, py)) return box
        }
        return null
    }

    private fun canPreview(box: KeyBox): Boolean =
        box.key.type == KeyType.CHARACTER && (box.face.top - dp(2f) - rowHeight) >= 0f

    private fun handleDown(id: Int, x: Float, y: Float) {
        val box = findBox(x, y) ?: return
        pointers.put(id, box)
        feedback(box.key.type)
        if (box.key.type == KeyType.DELETE) {
            listener?.onKeyDelete()
            repeatHandler.removeCallbacks(repeatRunnable)
            repeatHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
        } else {
            previewBox = if (canPreview(box)) box else null
            previewPointer = id
        }
        invalidate()
    }

    private fun handleMove(id: Int, x: Float, y: Float) {
        val current = pointers.get(id) ?: return
        if (current.key.type != KeyType.CHARACTER) return
        val next = findBox(x, y) ?: return
        if (next === current || next.key.type != KeyType.CHARACTER) return
        pointers.put(id, next)
        if (previewPointer == id) {
            previewBox = if (canPreview(next)) next else null
        }
        invalidate()
    }

    private fun handleUp(id: Int) {
        val box = pointers.get(id) ?: return
        pointers.remove(id)
        if (previewPointer == id) {
            previewBox = null
            previewPointer = -1
        }
        if (box.key.type == KeyType.DELETE) {
            repeatHandler.removeCallbacks(repeatRunnable)
        } else {
            perform(box.key)
        }
        invalidate()
    }

    private fun cancelTouches() {
        repeatHandler.removeCallbacks(repeatRunnable)
        pointers.clear()
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
        v.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun playSound(type: KeyType) {
        val effect = when (type) {
            KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        audioManager?.playSoundEffect(effect, -1f)
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
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 45L
        const val DOUBLE_TAP_MS = 350L
        const val VIBRATE_MS = 18L
        const val FONT_PATH = "fonts/AlteHaasGroteskBold.ttf"
    }
}
