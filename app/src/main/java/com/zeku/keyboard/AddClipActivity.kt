package com.zeku.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Kotak isian untuk menambah teks baru ke papan klip (tombol + di papan klip).
 * Dibuka dari keyboard karena keyboard sendiri tidak bisa menampilkan kotak isian.
 */
class AddClipActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val input = EditText(this).apply {
            hint = getString(R.string.clip_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.clip_add_title)
            .setView(frame)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    ClipboardStore.addText(this, text)
                    Toast.makeText(this, R.string.toast_clip_added, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .setOnDismissListener { finish() }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
