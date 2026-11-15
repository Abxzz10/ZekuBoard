package com.zeku.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Pencari saran kata dari awalan, memakai daftar kata di assets/words_id.txt
 * (satu kata per baris, huruf kecil). Dimuat di latar belakang supaya keyboard tidak tertahan;
 * sebelum selesai dimuat atau bila file tidak ada, saran kosong.
 *
 * Daftar ini belum punya data frekuensi, jadi urutan saran: kata yang lebih pendek dulu,
 * lalu urutan abjad.
 */
class WordSuggester(private val context: Context) {

    @Volatile
    private var words: Array<String> = emptyArray()

    /** Muat daftar kata di thread terpisah. [onLoaded] dipanggil di thread utama setelah selesai. */
    fun loadAsync(onLoaded: () -> Unit) {
        Thread {
            val list = try {
                context.assets.open(FILE_PATH).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.map { it.trim().lowercase() }.filter { it.length >= 2 }.toList()
                }
            } catch (e: Exception) {
                emptyList()
            }
            // Pencarian biner butuh urutan abjad; diurutkan lagi supaya aman bila file diganti.
            words = list.sorted().toTypedArray()
            Handler(Looper.getMainLooper()).post(onLoaded)
        }.start()
    }

    /** Sampai [limit] kata yang diawali [prefix] (huruf kecil). Kata yang sama persis ikut bila ada. */
    fun suggest(prefix: String, limit: Int = 3): List<String> {
        val a = words
        if (prefix.isEmpty() || a.isEmpty()) return emptyList()

        val best = ArrayList<String>(limit)
        var i = lowerBound(a, prefix)
        while (i < a.size && a[i].startsWith(prefix)) {
            val w = a[i]
            if (best.size < limit) {
                insertByLength(best, w)
            } else if (w.length < best[best.size - 1].length) {
                best.removeAt(best.size - 1)
                insertByLength(best, w)
            }
            i++
        }
        return best
    }

    /** Indeks pertama yang tidak lebih kecil dari [key]. */
    private fun lowerBound(a: Array<String>, key: String): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Sisipkan menurut panjang (pendek dulu); yang panjangnya sama tetap berurutan abjad. */
    private fun insertByLength(list: ArrayList<String>, word: String) {
        var pos = list.size
        while (pos > 0 && list[pos - 1].length > word.length) pos--
        list.add(pos, word)
    }

    private companion object {
        const val FILE_PATH = "words_id.txt"
    }
}
