package com.zeku.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Pencari saran kata dari awalan, memakai dua sumber:
 * 1. Kata yang dipelajari dari ketikan pengguna ([LearnedWords]), urut dari yang paling sering.
 * 2. Daftar kata di assets/words_id.txt (satu kata per baris, huruf kecil). Daftar ini belum punya
 *    data frekuensi, jadi urutannya: kata yang lebih pendek dulu, lalu urutan abjad.
 *
 * Daftar kata dimuat di latar belakang supaya keyboard tidak tertahan; sebelum selesai dimuat
 * atau bila file tidak ada, hanya kata yang dipelajari yang bisa muncul.
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

    /** Apakah [word] (huruf kecil) ada di daftar kata bawaan. */
    fun isKnownWord(word: String): Boolean {
        val a = words
        return a.isNotEmpty() && java.util.Arrays.binarySearch(a, word) >= 0
    }

    /**
     * Sampai [limit] kata yang diawali [prefix] (huruf kecil). Kata yang sama persis ikut bila ada.
     * Bila [useLearned] menyala, kata yang dipelajari dari ketikan didahulukan.
     */
    fun suggest(prefix: String, limit: Int = 3, useLearned: Boolean = true): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val result = ArrayList<String>(limit)

        if (useLearned) {
            // Kata di luar daftar bawaan (nama, singkatan, salah ketik) baru dianggap setelah
            // diketik beberapa kali, supaya sekali salah ketik tidak langsung jadi saran.
            val matches = LearnedWords.matching(prefix)
                .filter { it.second >= MIN_COUNT_UNKNOWN || isKnownWord(it.first) }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            for (m in matches) {
                if (result.size >= limit) break
                result.add(m.first)
            }
        }

        if (result.size < limit) {
            // Ambil lebih banyak dari perlu, lalu buang kata yang sudah ada dari hasil belajar.
            for (w in fromDictionary(prefix, limit + result.size)) {
                if (result.size >= limit) break
                if (w !in result) result.add(w)
            }
        }
        return result
    }

    private fun fromDictionary(prefix: String, limit: Int): List<String> {
        val a = words
        if (a.isEmpty()) return emptyList()
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
        const val MIN_COUNT_UNKNOWN = 2
    }
}
