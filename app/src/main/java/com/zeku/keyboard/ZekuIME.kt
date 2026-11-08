package com.zeku.keyboard

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.core.view.WindowCompat
import kotlin.math.abs

/**
 * Layanan keyboard ZekuBoard. Menerima aksi tombol dari [ZekuKeyboardView]
 * dan meneruskannya ke kolom teks yang sedang aktif.
 */
class ZekuIME : InputMethodService(), ZekuKeyboardView.Listener {

    /** Data satu gestur seleksi (Shift + hapus). Hanya hidup selama gestur berlangsung. */
    private class SelSession(
        val anchor: Int,
        val before: String,
        val after: String
    ) {
        var lo = anchor
        var hi = anchor
        var expected = ""
    }

    private var keyboardView: ZekuKeyboardView? = null
    private var session: SelSession? = null

    // Posisi seleksi terakhir yang dilaporkan kolom teks.
    private var trackedStart = -1
    private var trackedEnd = -1

    override fun onCreateInputView(): View {
        val view = ZekuKeyboardView(this)
        view.listener = this
        keyboardView = view
        return view
    }

    /** Jangan pakai mode layar penuh di landscape, supaya aplikasi di belakangnya tetap terlihat. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        trackedStart = info?.initialSelStart ?: -1
        trackedEnd = info?.initialSelEnd ?: -1
        val view = keyboardView ?: return

        view.applyPrefs()
        view.applyEnterKind(enterKindFor(info?.imeOptions ?: 0))
        if (!restarting) {
            view.showLayout(startLayoutFor(info?.inputType ?: InputType.TYPE_NULL))
            view.applyShift(ShiftState.OFF)
        }
        refreshAutoShift()
        applyNavigationBar()
    }

    /** Fokus berpindah: gestur seleksi yang belum selesai dibuang tanpa menyentuh teks. */
    override fun onFinishInput() {
        session = null
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        trackedStart = newSelStart
        trackedEnd = newSelEnd
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardView?.applyPrefs()
        applyNavigationBar()
    }

    // ------------------------------------------------ Aksi dari tampilan keyboard

    override fun onKeyText(text: String) {
        currentInputConnection?.commitText(text, 1)
        refreshAutoShift()
    }

    override fun onKeyDelete() {
        // Event tombol DEL paling kompatibel: menghapus pilihan teks atau satu karakter.
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        refreshAutoShift()
    }

    override fun onKeyEnter() {
        val ic = currentInputConnection ?: return
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        if (noEnterAction ||
            action == EditorInfo.IME_ACTION_NONE ||
            action == EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        } else {
            ic.performEditorAction(action)
        }
        refreshAutoShift()
    }

    // ------------------------------------------------------------ Gestur spasi

    /**
     * Pindah kursor lewat tombol panah kiri/kanan. Kolom teks sendiri yang menjaga
     * batas awal dan akhir teks, jadi kursor tidak bisa keluar dari teks.
     */
    override fun onCursorMove(steps: Int) {
        if (steps == 0 || currentInputConnection == null) return
        val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps).coerceAtMost(MAX_STEPS_PER_MOVE)) {
            sendDownUpKeyEvents(key)
        }
        refreshAutoShift()
    }

    // ------------------------------------------------------ Gestur Shift + hapus

    override fun onSelectBegin(): Boolean {
        session = null
        val ic = currentInputConnection ?: return false
        val pos = cursorPosition(ic)
        if (pos < 0) return false

        // Kalau sudah ada teks terblok, ciutkan dulu ke posisi kursor (ujung blok).
        val existing = ic.getSelectedText(0)
        if (!existing.isNullOrEmpty()) ic.setSelection(pos, pos)

        val before = ic.getTextBeforeCursor(MAX_SELECT_CHARS, 0)?.toString() ?: return false
        val after = ic.getTextAfterCursor(MAX_SELECT_CHARS, 0)?.toString() ?: return false
        // Pemeriksaan kewajaran: posisi mutlak tidak mungkin lebih kecil dari teks sebelum kursor.
        if (pos < before.length) return false

        session = SelSession(pos, before, after)
        return true
    }

    override fun onSelectChange(chars: Int) {
        val s = session ?: return
        val ic = currentInputConnection ?: return

        val lo: Int
        val hi: Int
        val text: String
        if (chars < 0) {
            var start = (s.before.length + chars).coerceAtLeast(0)
            // Jangan membelah pasangan surrogate (emoji): ikutkan karakter utuhnya.
            if (start > 0 && start < s.before.length &&
                Character.isLowSurrogate(s.before[start]) &&
                Character.isHighSurrogate(s.before[start - 1])
            ) {
                start -= 1
            }
            lo = s.anchor - (s.before.length - start)
            hi = s.anchor
            text = s.before.substring(start)
        } else {
            var end = chars.coerceAtMost(s.after.length)
            if (end > 0 && end < s.after.length &&
                Character.isLowSurrogate(s.after[end]) &&
                Character.isHighSurrogate(s.after[end - 1])
            ) {
                end += 1
            }
            lo = s.anchor
            hi = s.anchor + end
            text = s.after.substring(0, end)
        }

        if (lo == s.lo && hi == s.hi) return
        s.lo = lo
        s.hi = hi
        s.expected = text
        ic.setSelection(lo, hi)
    }

    override fun onSelectCommit() {
        val s = session ?: return
        session = null
        val ic = currentInputConnection ?: return
        if (s.hi <= s.lo) return

        // Hapus hanya kalau teks yang terblok persis sama dengan yang dihitung.
        val selected = ic.getSelectedText(0)?.toString()
        if (selected != null && selected == s.expected) {
            ic.commitText("", 1)
        } else {
            ic.setSelection(s.anchor, s.anchor)
        }
        refreshAutoShift()
    }

    override fun onSelectCancel() {
        val s = session ?: return
        session = null
        currentInputConnection?.setSelection(s.anchor, s.anchor)
    }

    /** Posisi kursor (ujung seleksi) dalam teks, atau -1 kalau kolom teks tidak memberi tahu. */
    private fun cursorPosition(ic: InputConnection): Int {
        if (trackedEnd >= 0) return trackedEnd
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return -1
        if (et.selectionEnd < 0) return -1
        return et.startOffset + et.selectionEnd
    }

    // ------------------------------------------------------------------ Bantuan

    /** Huruf besar otomatis di awal kalimat, sesuai permintaan kolom teks. */
    private fun refreshAutoShift() {
        val view = keyboardView ?: return
        if (view.shiftState == ShiftState.LOCK) return
        val info = currentInputEditorInfo ?: return
        val caps = if (info.inputType == InputType.TYPE_NULL) {
            0
        } else {
            currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        }
        view.applyShift(if (caps != 0) ShiftState.ONCE else ShiftState.OFF)
    }

    private fun startLayoutFor(inputType: Int): LayoutMode =
        when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> LayoutMode.SYMBOLS_1
            else -> LayoutMode.LETTERS
        }

    private fun enterKindFor(options: Int): EnterKind {
        if ((options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return EnterKind.ENTER
        return when (options and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> EnterKind.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterKind.SEND
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> EnterKind.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterKind.DONE
            else -> EnterKind.ENTER
        }
    }

    /** Samakan warna navigation bar dengan keyboard (hitam / abu terang). */
    private fun applyNavigationBar() {
        val view = keyboardView ?: return
        runCatching {
            val w = window?.window ?: return
            w.navigationBarColor = view.themeBackground
            WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars =
                !view.isDarkTheme
        }
    }

    private companion object {
        /** Batas karakter yang bisa diblok dalam satu gestur. */
        const val MAX_SELECT_CHARS = 1000

        /** Batas langkah kursor per satu kali geser, supaya geseran cepat tetap aman. */
        const val MAX_STEPS_PER_MOVE = 30
    }
}
