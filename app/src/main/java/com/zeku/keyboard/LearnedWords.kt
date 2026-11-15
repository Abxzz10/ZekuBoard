package com.zeku.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.TreeMap
import java.util.concurrent.Executors

/**
 * Kata yang dipelajari dari ketikan pengguna: kata dan berapa kali selesai diketik.
 *
 * Dipakai bersama oleh keyboard dan halaman pengaturan (satu proses), jadi penghapusan dari
 * pengaturan langsung berlaku. Data hanya ada di perangkat: disimpan di folder khusus yang tidak ikut
 * pencadangan otomatis, dan tidak pernah dikirim ke mana pun. Semua akses data di memori terjadi
 * di thread utama; thread lain hanya membaca dan menulis file.
 */
object LearnedWords {

    private const val FILE_NAME = "learned_words.txt"
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 24
    private const val MAX_WORDS = 5000
    private const val MAX_COUNT = 1000
    private const val SAVE_DELAY_MS = 3000L

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val saveTask = Runnable { saveNow() }

    // null = belum selesai dimuat. Hanya disentuh di thread utama.
    private var counts: TreeMap<String, Int>? = null
    private var dirty = false
    private var appContext: Context? = null

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)

    /** Muat dari file di latar belakang. [onLoaded] dipanggil di thread utama. */
    fun loadAsync(context: Context, onLoaded: () -> Unit) {
        val app = context.applicationContext
        appContext = app
        if (counts != null) {
            onLoaded()
            return
        }
        io.execute {
            val map = TreeMap<String, Int>()
            try {
                val f = file(app)
                if (f.exists()) {
                    f.forEachLine { line ->
                        val cut = line.indexOf(' ')
                        if (cut > 0) {
                            val word = line.substring(0, cut)
                            val n = line.substring(cut + 1).trim().toIntOrNull()
                            if (n != null && n in 1..MAX_COUNT && isValid(word)) map[word] = n
                        }
                    }
                }
            } catch (e: Exception) {
                // File rusak atau tidak terbaca: mulai dari kosong.
            }
            handler.post {
                if (counts == null) counts = map
                onLoaded()
            }
        }
    }

    private fun isValid(word: String): Boolean =
        word.length in MIN_LENGTH..MAX_LENGTH && word.all { it in 'a'..'z' }

    /** Kata dipelajari yang diawali [prefix] (huruf kecil), beserta jumlahnya. */
    fun matching(prefix: String): List<Pair<String, Int>> {
        val map = counts ?: return emptyList()
        if (map.isEmpty() || prefix.isEmpty()) return emptyList()
        return map.subMap(prefix, prefix + '\uffff').map { it.key to it.value }
    }

    /** Catat satu kata selesai diketik (huruf kecil). Diabaikan bila belum dimuat atau tidak valid. */
    fun learn(word: String) {
        val map = counts ?: return
        if (!isValid(word)) return
        map[word] = ((map[word] ?: 0) + 1).coerceAtMost(MAX_COUNT)
        if (map.size > MAX_WORDS) prune(map)
        dirty = true
        handler.removeCallbacks(saveTask)
        handler.postDelayed(saveTask, SAVE_DELAY_MS)
    }

    /** Buang kata yang paling jarang dipakai sampai tinggal 90% batas. */
    private fun prune(map: TreeMap<String, Int>) {
        val target = MAX_WORDS * 9 / 10
        var limit = 1
        while (map.size > target && limit <= MAX_COUNT) {
            val it = map.entries.iterator()
            while (it.hasNext() && map.size > target) {
                if (it.next().value <= limit) it.remove()
            }
            limit++
        }
    }

    /** Simpan sekarang bila ada perubahan yang belum tersimpan (dipanggil saat kolom teks ditutup). */
    fun flush() {
        handler.removeCallbacks(saveTask)
        if (dirty) saveNow()
    }

    private fun saveNow() {
        val map = counts ?: return
        val app = appContext ?: return
        dirty = false
        val text = buildString {
            for ((word, n) in map) append(word).append(' ').append(n).append('\n')
        }
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

    /** Hapus semua kata yang dipelajari, di memori dan di file. */
    fun clear(context: Context) {
        val app = context.applicationContext
        appContext = app
        counts?.clear()
        dirty = false
        handler.removeCallbacks(saveTask)
        // Antrean tunggal: penghapusan selalu berjalan setelah penyimpanan yang sudah diantrekan.
        io.execute {
            try {
                file(app).delete()
            } catch (e: Exception) {
                // Abaikan.
            }
        }
    }
}
