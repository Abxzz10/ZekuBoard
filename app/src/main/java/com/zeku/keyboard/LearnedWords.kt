package com.zeku.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.TreeMap
import java.util.concurrent.Executors

/**
 * Yang dipelajari dari ketikan pengguna:
 * - kata dan berapa kali selesai diketik (untuk mengurutkan saran), dan
 * - pasangan "kata sebelumnya lalu kata berikutnya" beserta jumlahnya (untuk menebak kata berikutnya).
 *
 * Dipakai bersama oleh keyboard dan halaman pengaturan (satu proses), jadi penghapusan dari
 * pengaturan langsung berlaku. Data hanya ada di perangkat: disimpan di folder khusus yang tidak ikut
 * pencadangan otomatis, dan tidak pernah dikirim ke mana pun. Semua akses data di memori terjadi
 * di thread utama; thread lain hanya membaca dan menulis file.
 *
 * Format file: baris "kata jumlah" untuk kata, baris "sebelum sesudah jumlah" untuk pasangan.
 */
object LearnedWords {

    private const val FILE_NAME = "learned_words.txt"
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 24
    private const val MAX_WORDS = 5000
    private const val MAX_PAIRS = 8000
    private const val MAX_COUNT = 1000
    private const val SAVE_DELAY_MS = 3000L

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val saveTask = Runnable { saveNow() }

    // null = belum selesai dimuat. Hanya disentuh di thread utama.
    private var counts: TreeMap<String, Int>? = null
    private var pairs = HashMap<String, HashMap<String, Int>>()
    private var pairTotal = 0
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
            val wordMap = TreeMap<String, Int>()
            val pairMap = HashMap<String, HashMap<String, Int>>()
            var total = 0
            try {
                val f = file(app)
                if (f.exists()) {
                    f.forEachLine { line ->
                        val parts = line.trim().split(' ')
                        if (parts.size == 2) {
                            val n = parts[1].toIntOrNull()
                            if (n != null && n in 1..MAX_COUNT && isValid(parts[0])) wordMap[parts[0]] = n
                        } else if (parts.size == 3) {
                            val n = parts[2].toIntOrNull()
                            if (n != null && n in 1..MAX_COUNT && isValid(parts[0]) && isValid(parts[1])) {
                                pairMap.getOrPut(parts[0]) { HashMap() }[parts[1]] = n
                                total++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // File rusak atau tidak terbaca: mulai dari kosong.
            }
            handler.post {
                if (counts == null) {
                    counts = wordMap
                    pairs = pairMap
                    pairTotal = total
                }
                onLoaded()
            }
        }
    }

    private fun isValid(word: String): Boolean =
        word.length in MIN_LENGTH..MAX_LENGTH && word.all { it in 'a'..'z' }

    /** Berapa kali [word] (huruf kecil) selesai diketik; 0 bila belum pernah. */
    fun countOf(word: String): Int = counts?.get(word) ?: 0

    /** Kata dipelajari yang diawali [prefix] (huruf kecil), beserta jumlahnya. */
    fun matching(prefix: String): List<Pair<String, Int>> {
        val map = counts ?: return emptyList()
        if (map.isEmpty() || prefix.isEmpty()) return emptyList()
        return map.subMap(prefix, prefix + '\uffff').map { it.key to it.value }
    }

    /** Kata yang pernah mengikuti [prev] (huruf kecil), beserta jumlahnya. */
    fun nextFor(prev: String): List<Pair<String, Int>> =
        pairs[prev]?.map { it.key to it.value } ?: emptyList()

    /** Catat satu kata selesai diketik (huruf kecil). Diabaikan bila belum dimuat atau tidak valid. */
    fun learn(word: String) {
        val map = counts ?: return
        if (!isValid(word)) return
        map[word] = ((map[word] ?: 0) + 1).coerceAtMost(MAX_COUNT)
        if (map.size > MAX_WORDS) prune(map)
        markDirty()
    }

    /** Catat bahwa [next] diketik tepat setelah [prev] (keduanya huruf kecil). */
    fun learnPair(prev: String, next: String) {
        if (counts == null || !isValid(prev) || !isValid(next)) return
        val inner = pairs.getOrPut(prev) { HashMap() }
        val old = inner[next]
        inner[next] = ((old ?: 0) + 1).coerceAtMost(MAX_COUNT)
        if (old == null) pairTotal++
        if (pairTotal > MAX_PAIRS) prunePairs()
        markDirty()
    }

    private fun markDirty() {
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

    /** Buang pasangan yang paling jarang muncul sampai tinggal 90% batas. */
    private fun prunePairs() {
        val target = MAX_PAIRS * 9 / 10
        var limit = 1
        while (pairTotal > target && limit <= MAX_COUNT) {
            val outer = pairs.entries.iterator()
            while (outer.hasNext() && pairTotal > target) {
                val inner = outer.next().value
                val it = inner.entries.iterator()
                while (it.hasNext() && pairTotal > target) {
                    if (it.next().value <= limit) {
                        it.remove()
                        pairTotal--
                    }
                }
                if (inner.isEmpty()) outer.remove()
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
            for ((prev, inner) in pairs) {
                for ((next, n) in inner) {
                    append(prev).append(' ').append(next).append(' ').append(n).append('\n')
                }
            }
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

    /** Hapus semua yang dipelajari, di memori dan di file. */
    fun clear(context: Context) {
        val app = context.applicationContext
        appContext = app
        counts?.clear()
        pairs = HashMap()
        pairTotal = 0
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
