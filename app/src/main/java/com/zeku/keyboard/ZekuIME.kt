package com.zeku.keyboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.SystemClock
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
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

    // Saran kata: daftar kata dimuat di latar belakang; kolom sandi dan sejenisnya tanpa saran.
    private lateinit var suggester: WordSuggester
    private var suggestionsAllowed = true

    // Belajar dari ketikan: hanya bila pengaturannya menyala dan kolom teks mengizinkan.
    private val prefs by lazy { Prefs(this) }

    // Papan klip: teks yang disalin dicatat selama keyboard ini aktif.
    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager }
    private val clipChangedListener =
        ClipboardManager.OnPrimaryClipChangedListener { captureClipboard(fromListener = true) }
    private var learningAllowed = true

    override fun onCreate() {
        super.onCreate()
        suggester = WordSuggester(applicationContext)
        suggester.loadAsync { refreshSuggestions(userDriven = false) }
        LearnedWords.loadAsync(applicationContext) { refreshSuggestions(userDriven = false) }
        ClipboardStore.loadAsync(applicationContext) { }
        clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
        LearnedWords.flush()
        ClipboardStore.flush()
        ScreenshotStore.flush()
        super.onDestroy()
    }

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
        keyboardView?.setHasSelection(trackedStart >= 0 && trackedEnd >= 0 && trackedStart != trackedEnd)
        suggestionsAllowed = suggestionsAllowedFor(info?.inputType ?: InputType.TYPE_NULL)
        captureClipboard(fromListener = false)
        ClipboardStore.expire(prefs.clipMaxAgeMs())
        learningAllowed = suggestionsAllowed &&
            ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        val view = keyboardView ?: return

        view.applyPrefs()
        view.applyEnterKind(enterKindFor(info?.imeOptions ?: 0))
        if (!restarting) {
            view.showLayout(startLayoutFor(info?.inputType ?: InputType.TYPE_NULL))
            view.applyShift(ShiftState.OFF)
            view.resetTopBar()
            view.closeEmoji()
            view.closeClipboard()
            view.closeEdit()
        }
        refreshAutoShift()
        applyNavigationBar()
        refreshSuggestions(userDriven = false)
    }

    /** Fokus berpindah: gestur seleksi yang belum selesai dibuang tanpa menyentuh teks. */
    override fun onFinishInput() {
        session = null
        // Simpan kata yang dipelajari dan papan klip sebelum kolom teks ditutup.
        LearnedWords.flush()
        ClipboardStore.flush()
        ScreenshotStore.flush()
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
        // Ada blok teks atau tidak: dipakai tombol Pilih semua / Potong di panel kontrol kursor.
        keyboardView?.setHasSelection(newSelStart >= 0 && newSelEnd >= 0 && newSelStart != newSelEnd)
        // Kursor berpindah (ketik, hapus, geser, ketuk di teks, atau kolom dikosongkan aplikasi):
        // bilah atas dan saran dihitung ulang dari teks di sekitar kursor.
        refreshSuggestions(userDriven = newSelStart != oldSelStart || newSelEnd != oldSelEnd)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardView?.applyPrefs()
        applyNavigationBar()
    }

    // ------------------------------------------------ Aksi dari tampilan keyboard

    override fun onKeyText(text: String) {
        // Spasi atau tanda baca menutup sebuah kata: catat kata itu sebelum teks baru masuk.
        if (isWordBoundary(text)) learnWordBeforeCursor()
        currentInputConnection?.commitText(text, 1)
        refreshAutoShift()
        refreshSuggestions()
    }

    /** Ketuk saran: kata yang sedang diketik diganti kata utuh, lalu diberi satu spasi. */
    override fun onSuggestionPick(word: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(MAX_WORD_CHARS, 0)?.toString() ?: ""
        val typedLength = before.takeLastWhile { isAsciiLetter(it) }.length
        ic.beginBatchEdit()
        if (typedLength > 0) ic.deleteSurroundingText(typedLength, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (canLearn()) {
            LearnedWords.learn(word.lowercase())
            val prev = previousWordForNext(before.dropLast(typedLength))
            if (prev != null) LearnedWords.learnPair(prev, word.lowercase())
        }
        refreshAutoShift()
        refreshSuggestions()
    }

    override fun onKeyDelete() {
        // Event tombol DEL paling kompatibel: menghapus pilihan teks atau satu karakter.
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        refreshAutoShift()
        refreshSuggestions()
    }

    override fun onKeyEnter() {
        val ic = currentInputConnection ?: return
        learnWordBeforeCursor()
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
        refreshSuggestions()
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
        refreshSuggestions()
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

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /** Saran kata hanya untuk kolom teks biasa: bukan sandi, email, alamat web, angka, atau yang menolak saran. */
    private fun suggestionsAllowedFor(inputType: Int): Boolean {
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI -> return false
        }
        return (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
    }

    /**
     * Hitung kandidat dari teks tepat sebelum kursor, lalu kirim ke bilah atas:
     * - ada huruf yang sedang diketik: lengkapi kata, atau perbaiki salah ketik bila tidak ada kata cocok;
     * - tepat satu spasi setelah sebuah kata: tebak kata berikutnya;
     * - selain itu atau kursor di tengah kata: tanpa kandidat.
     * [userDriven] salah untuk pembaruan otomatis (kolom baru dibuka, data selesai dimuat).
     */
    private fun refreshSuggestions(userDriven: Boolean = true) {
        val view = keyboardView ?: return
        val ic = currentInputConnection
        val before = ic?.getTextBeforeCursor(MAX_WORD_CHARS, 0)?.toString()
        if (ic == null || before == null || !suggestionsAllowed) {
            view.setTopBarState(before ?: "", emptyList(), userDriven)
            return
        }
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        val midWord = after.isNotEmpty() && isAsciiLetter(after[0])
        val useLearned = prefs.learnTyping
        val typed = before.takeLastWhile { isAsciiLetter(it) }

        val words: List<String> = when {
            midWord -> emptyList()
            typed.isNotEmpty() -> {
                val lower = typed.lowercase()
                var found = suggester.suggest(lower, useLearned = useLearned)
                if (found.isEmpty()) found = suggester.correct(lower, useLearned = useLearned)
                found.map { matchCase(typed, it) }
            }
            else -> {
                val prev = previousWordForNext(before)
                if (prev == null) emptyList() else suggester.predictNext(prev, useLearned = useLearned)
            }
        }
        view.setTopBarState(before, words, userDriven)
    }

    /** Kata sebelum satu spasi di akhir [before] (untuk menebak kata berikutnya), atau null. */
    private fun previousWordForNext(before: String): String? {
        if (!before.endsWith(" ") || before.endsWith("  ")) return null
        val word = before.dropLast(1).takeLastWhile { isAsciiLetter(it) }
        return if (word.length >= LearnedWords.MIN_LENGTH) word.lowercase() else null
    }

    /** Saran mengikuti huruf besar yang diketik: Awal Besar tetap Awal Besar, SEMUA BESAR tetap besar. */
    private fun matchCase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { it in 'A'..'Z' } -> word.uppercase()
        typed[0] in 'A'..'Z' -> word.substring(0, 1).uppercase() + word.substring(1)
        else -> word
    }

    /**
     * Catat teks di papan klip sistem ke riwayat. Teks yang ditandai sensitif oleh aplikasi asalnya
     * (misalnya sandi dari pengelola sandi) tidak dicatat.
     */
    private fun captureClipboard(fromListener: Boolean) {
        if (!prefs.clipboardHistory) return
        val manager = clipboardManager ?: return
        val text: String? = try {
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) {
                null
            } else if (clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true) {
                null
            } else {
                clip.getItemAt(0).coerceToText(this)?.toString()
            }
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrEmpty()) return
        ClipboardStore.capture(text, fromListener)
    }

    /** Aksi dari panel kontrol kursor: gerak kursor, pilih, salin, tempel, potong. */
    override fun onEditAction(action: EditAction, extend: Boolean) {
        val ic = currentInputConnection ?: return
        when (action) {
            EditAction.LEFT -> sendNavKey(ic, KeyEvent.KEYCODE_DPAD_LEFT, extend)
            EditAction.RIGHT -> sendNavKey(ic, KeyEvent.KEYCODE_DPAD_RIGHT, extend)
            EditAction.UP -> sendNavKey(ic, KeyEvent.KEYCODE_DPAD_UP, extend)
            EditAction.DOWN -> sendNavKey(ic, KeyEvent.KEYCODE_DPAD_DOWN, extend)
            EditAction.HOME -> sendNavKey(ic, KeyEvent.KEYCODE_MOVE_HOME, extend)
            EditAction.END -> sendNavKey(ic, KeyEvent.KEYCODE_MOVE_END, extend)
            EditAction.SELECT_ALL -> selectAllText(ic)
            EditAction.COPY -> copySelection(ic, cut = false)
            EditAction.CUT -> copySelection(ic, cut = true)
            EditAction.PASTE -> pasteFromClipboard(ic)
        }
        refreshAutoShift()
        refreshSuggestions()
    }

    /** Kirim tombol arah. [extend] menahan Shift supaya blok teks ikut diperluas. */
    private fun sendNavKey(ic: InputConnection, keyCode: Int, extend: Boolean) {
        val now = SystemClock.uptimeMillis()
        val meta = if (extend) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        if (extend) {
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta))
        }
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        if (extend) {
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
        }
    }

    private fun selectAllText(ic: InputConnection) {
        if (ic.performContextMenuAction(android.R.id.selectAll)) return
        // Cadangan bila kolom teks tidak mengerti aksi menu: blok dari awal sampai akhir teks.
        val text = ic.getExtractedText(ExtractedTextRequest(), 0)?.text ?: return
        ic.setSelection(0, text.length)
    }

    /** Salin teks yang diblok ke papan klip sistem (tercatat juga di papan klip keyboard); [cut] juga menghapusnya. */
    private fun copySelection(ic: InputConnection, cut: Boolean) {
        val selected = ic.getSelectedText(0)?.toString()
        if (selected.isNullOrEmpty()) return
        clipboardManager?.setPrimaryClip(ClipData.newPlainText("", selected))
        if (cut) ic.commitText("", 1)
    }

    /** Tempel teks dari papan klip sistem; bila ada blok teks, blok itu diganti. */
    private fun pasteFromClipboard(ic: InputConnection) {
        val clip: ClipData? = try {
            clipboardManager?.primaryClip
        } catch (e: Exception) {
            null
        }
        if (clip == null || clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) return
        ic.commitText(text, 1)
    }

    /** Tempel screenshot dari papan klip sebagai gambar, bila kolom teks menerima gambar. */
    override fun onPasteImage(uri: Uri, mime: String) {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        val accepted = EditorInfoCompat.getContentMimeTypes(info)
        if (accepted.none { ClipDescription.compareMimeTypes(mime, it) }) {
            Toast.makeText(this, R.string.shot_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= 25) {
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        } else {
            0
        }
        val content = InputContentInfoCompat(uri, ClipDescription("screenshot", arrayOf(mime)), null)
        if (!InputConnectionCompat.commitContent(ic, info, content, flags, null)) {
            Toast.makeText(this, R.string.shot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun canLearn(): Boolean = learningAllowed && prefs.learnTyping

    /** Spasi, baris baru, atau tanda baca penutup: tanda satu kata selesai diketik. */
    private fun isWordBoundary(text: String): Boolean =
        text.length == 1 && (text[0].isWhitespace() || text[0] in ".,!?;:)]}\"")

    /** Catat kata yang baru selesai diketik tepat sebelum kursor (bukan kata yang dipotong di tengah). */
    private fun learnWordBeforeCursor() {
        if (!canLearn()) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(MAX_WORD_CHARS, 0)?.toString() ?: return
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        if (after.isNotEmpty() && isAsciiLetter(after[0])) return
        val typed = before.takeLastWhile { isAsciiLetter(it) }
        if (typed.length in LearnedWords.MIN_LENGTH..LearnedWords.MAX_LENGTH) {
            val word = typed.lowercase()
            LearnedWords.learn(word)
            // Pasangan "kata sebelumnya lalu kata ini" bila dipisah satu spasi.
            val prev = previousWordForNext(before.dropLast(typed.length))
            if (prev != null) LearnedWords.learnPair(prev, word)
        }
    }

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

        /** Panjang teks sebelum kursor yang dibaca untuk mencari kata yang sedang diketik. */
        const val MAX_WORD_CHARS = 32

        /** Penanda sensitif pada klip (ClipDescription.EXTRA_IS_SENSITIVE, ada sejak Android 13). */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
