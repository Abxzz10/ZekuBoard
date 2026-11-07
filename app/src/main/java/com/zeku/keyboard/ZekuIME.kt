package com.zeku.keyboard

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.WindowCompat

/**
 * Layanan keyboard ZekuBoard. Menerima aksi tombol dari [ZekuKeyboardView]
 * dan meneruskannya ke kolom teks yang sedang aktif.
 */
class ZekuIME : InputMethodService(), ZekuKeyboardView.Listener {

    private var keyboardView: ZekuKeyboardView? = null

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
}
