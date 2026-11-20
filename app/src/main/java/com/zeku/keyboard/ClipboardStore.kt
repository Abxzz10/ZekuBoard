package com.zeku.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Satu teks di papan klip. [pinned] = disematkan: tidak ikut terbuang saat riwayat penuh. */
class ClipItem(val id: Long, val text: String, var pinned: Boolean, val time: Long) {

    /** Teks satu baris untuk kartu: semua spasi dan baris baru dipadatkan jadi satu spasi. */
    val preview: String = text.replace(WHITESPACE, " ").trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Riwayat papan klip: teks yang disalin pengguna, dengan sematan.
 *
 * Dipakai bersama oleh keyboard dan halaman pengaturan (satu proses). Data hanya ada di perangkat:
 * disimpan di folder khusus yang tidak ikut pencadangan otomatis, tidak pernah dikirim ke mana pun.
 * Semua akses data di memori terjadi di thread utama; thread lain hanya membaca dan menulis file.
 */
object ClipboardStore {

    private const val FILE_NAME = "clipboard_history.json"
    const val MAX_TEXT = 10_000
    private const val MAX_UNPINNED = 40
    private const val MAX_PINNED = 30
    private const val SAVE_DELAY_MS = 2000L

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val saveTask = Runnable { saveNow() }

    private var loaded = false
    private val list = ArrayList<ClipItem>() // terbaru di depan
    private var lastSeen = ""
    private var nextId = 1L
    private var dirty = false
    private var appContext: Context? = null

    // Salinan yang masuk sebelum file selesai dimuat: (teks, dari pendengar papan klip).
    private val pending = ArrayList<Pair<String, Boolean>>()

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)

    /** Muat dari file di latar belakang. [onLoaded] dipanggil di thread utama. */
    fun loadAsync(context: Context, onLoaded: () -> Unit) {
        val app = context.applicationContext
        appContext = app
        if (loaded) {
            onLoaded()
            return
        }
        io.execute {
            val items = ArrayList<ClipItem>()
            var last = ""
            try {
                val f = file(app)
                if (f.exists()) {
                    val json = JSONObject(f.readText())
                    last = json.optString("last", "")
                    val arr = json.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val text = o.optString("t", "")
                        if (text.isEmpty() || text.length > MAX_TEXT) continue
                        items.add(ClipItem(o.optLong("id", i + 1L), text, o.optBoolean("p", false), o.optLong("ts", 0L)))
                    }
                }
            } catch (e: Exception) {
                // File rusak atau tidak terbaca: mulai dari kosong.
            }
            handler.post {
                if (!loaded) {
                    list.addAll(items)
                    lastSeen = last
                    nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1L
                    loaded = true
                    val queued = ArrayList(pending)
                    pending.clear()
                    for ((text, fromListener) in queued) capture(text, fromListener)
                }
                onLoaded()
            }
        }
    }

    /**
     * Catat teks yang ada di papan klip sistem. [fromListener] benar bila teks baru saja disalin
     * (selalu dicatat, termasuk salinan yang sama dua kali); salah bila hanya memeriksa saat
     * keyboard dibuka, supaya teks yang sudah dihapus pengguna tidak muncul lagi.
     */
    fun capture(text: String, fromListener: Boolean) {
        if (!loaded) {
            pending.add(text to fromListener)
            return
        }
        if (!fromListener && text == lastSeen) return
        lastSeen = text
        add(text)
    }

    /** Tambah teks buatan pengguna (dari tombol +), lalu simpan. Aman dipanggil walau riwayat belum dimuat. */
    fun addText(context: Context, text: String) {
        loadAsync(context) {
            add(text)
            flush()
        }
    }

    private fun add(text: String) {
        if (text.isBlank() || text.length > MAX_TEXT) return
        var id = nextId
        var pinned = false
        val existing = list.indexOfFirst { it.text == text }
        if (existing >= 0) {
            // Teks yang sama disalin lagi: naik ke paling atas, sematannya tetap.
            id = list[existing].id
            pinned = list[existing].pinned
            list.removeAt(existing)
        } else {
            nextId++
        }
        list.add(0, ClipItem(id, text, pinned, System.currentTimeMillis()))
        var unpinned = list.count { !it.pinned }
        while (unpinned > MAX_UNPINNED) {
            list.removeAt(list.indexOfLast { !it.pinned })
            unpinned--
        }
        markDirty()
    }

    /**
     * Buang teks yang tidak disematkan dan lebih tua dari [maxAgeMs] milidetik.
     * [maxAgeMs] 0 atau kurang berarti tidak ada batas waktu.
     */
    fun expire(maxAgeMs: Long) {
        if (maxAgeMs <= 0L || !loaded) return
        val cutoff = System.currentTimeMillis() - maxAgeMs
        if (list.removeAll { !it.pinned && it.time < cutoff }) markDirty()
    }

    /** Daftar untuk ditampilkan: yang disematkan dulu, lalu sisanya; masing-masing terbaru di depan. */
    fun items(): List<ClipItem> = list.filter { it.pinned } + list.filter { !it.pinned }

    /** Sematkan atau lepas sematan semua item dengan id di [ids]. Batas sematan: [MAX_PINNED]. */
    fun setPinned(ids: Set<Long>, pinned: Boolean) {
        var pinnedCount = list.count { it.pinned }
        var changed = false
        for (item in list) {
            if (item.id !in ids || item.pinned == pinned) continue
            if (pinned) {
                if (pinnedCount >= MAX_PINNED) continue
                pinnedCount++
            } else {
                pinnedCount--
            }
            item.pinned = pinned
            changed = true
        }
        if (changed) markDirty()
    }

    fun delete(ids: Set<Long>) {
        if (list.removeAll { it.id in ids }) markDirty()
    }

    /** Hapus semua, termasuk yang disematkan. */
    fun clear(context: Context) {
        appContext = context.applicationContext
        list.clear()
        pending.clear()
        if (loaded) {
            markDirty()
        } else {
            val app = context.applicationContext
            io.execute {
                try {
                    file(app).delete()
                } catch (e: Exception) {
                    // Abaikan.
                }
            }
        }
    }

    private fun markDirty() {
        dirty = true
        handler.removeCallbacks(saveTask)
        handler.postDelayed(saveTask, SAVE_DELAY_MS)
    }

    /** Simpan sekarang bila ada perubahan yang belum tersimpan. */
    fun flush() {
        handler.removeCallbacks(saveTask)
        if (dirty) saveNow()
    }

    private fun saveNow() {
        val app = appContext ?: return
        if (!loaded) return
        dirty = false
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("t", item.text)
                    .put("p", item.pinned)
                    .put("ts", item.time)
            )
        }
        val text = JSONObject().put("last", lastSeen).put("items", arr).toString()
        io.execute {
            try {
                val f = file(app)
                val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (e: Exception) {
                // Gagal menyimpan: data di memori tetap dipakai, dicoba lagi pada perubahan berikutnya.
            }
        }
    }
}
