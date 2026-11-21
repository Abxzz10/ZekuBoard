package com.zeku.keyboard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Kotak isian untuk menambah teks baru ke papan klip (tombol + di papan klip).
 * Dibuka dari keyboard karena keyboard sendiri tidak bisa menampilkan kotak isian.
 * Tampilannya mengikuti gaya aplikasi: kartu, kolom isian, dan tombol yang sama.
 */
class AddClipActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_clip)

        val input = findViewById<EditText>(R.id.addInput)
        val save = findViewById<TextView>(R.id.addSave)

        // Ketuk di luar kartu atau Batal: tutup tanpa menyimpan.
        findViewById<View>(R.id.addRoot).setOnClickListener { finish() }
        findViewById<View>(R.id.addCancel).setOnClickListener { finish() }

        // Simpan baru aktif bila ada isinya.
        fun refreshSave() {
            val filled = input.text.toString().isNotBlank()
            save.isEnabled = filled
            save.alpha = if (filled) 1f else 0.4f
        }
        refreshSave()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshSave()
        })

        save.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                ClipboardStore.addText(this, text)
                Toast.makeText(this, R.string.toast_clip_added, Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        input.requestFocus()
    }
}
