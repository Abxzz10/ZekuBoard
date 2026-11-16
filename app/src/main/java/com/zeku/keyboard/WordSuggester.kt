package com.zeku.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Mesin saran kata, tiga kemampuan:
 * 1. [suggest]: melengkapi kata yang sedang diketik dari awalannya.
 * 2. [correct]: memperbaiki salah ketik (selisih satu huruf) bila awalan tidak cocok dengan kata mana pun.
 * 3. [predictNext]: menebak kata berikutnya setelah satu kata dan spasi.
 *
 * Sumber data: kata yang dipelajari dari ketikan pengguna ([LearnedWords]), daftar kata di
 * assets/words_id.txt (tanpa frekuensi: kata pendek dulu, lalu abjad), dan data awal pasangan kata di
 * assets/bigrams_id.txt (per baris: kata lalu kata-kata yang biasa mengikutinya, yang paling umum dulu).
 *
 * File dimuat di latar belakang supaya keyboard tidak tertahan; sebelum selesai dimuat atau bila
 * file tidak ada, hanya kata yang dipelajari yang bisa muncul.
 */
class WordSuggester(private val context: Context) {

    @Volatile
    private var words: Array<String> = emptyArray()

    @Volatile
    private var seedPairs: Map<String, List<String>> = emptyMap()

    /** Muat data di thread terpisah. [onLoaded] dipanggil di thread utama setelah selesai. */
    fun loadAsync(onLoaded: () -> Unit) {
        Thread {
            val list = try {
                context.assets.open(WORDS_PATH).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.map { it.trim().lowercase() }.filter { it.length >= 2 }.toList()
                }
            } catch (e: Exception) {
                emptyList()
            }
            // Pencarian biner butuh urutan abjad; diurutkan lagi supaya aman bila file diganti.
            words = list.sorted().toTypedArray()

            val pairs = HashMap<String, List<String>>()
            try {
                context.assets.open(PAIRS_PATH).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val parts = line.trim().lowercase().split(' ', '\t').filter { it.isNotEmpty() }
                        if (parts.size >= 2) pairs[parts[0]] = parts.drop(1)
                    }
                }
            } catch (e: Exception) {
                // Tanpa data awal: tebakan hanya dari kata yang dipelajari.
            }
            seedPairs = pairs

            Handler(Looper.getMainLooper()).post(onLoaded)
        }.start()
    }

    /** Apakah [word] (huruf kecil) ada di daftar kata bawaan. */
    fun isKnownWord(word: String): Boolean {
        val a = words
        return a.isNotEmpty() && java.util.Arrays.binarySearch(a, word) >= 0
    }

    // ---------------------------------------------------------------- 1. Melengkapi kata

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

    // ------------------------------------------------------------ 2. Koreksi salah ketik

    /**
     * Kata yang selisihnya satu huruf dari [word] (huruf kecil): satu huruf kelebihan, kurang,
     * salah, atau dua huruf bertukar. Dipakai bila tidak ada kata yang diawali [word].
     * Urutan: huruf pertama sama dulu, lalu panjang sama, lalu yang lebih sering diketik pengguna.
     */
    fun correct(word: String, limit: Int = 3, useLearned: Boolean = true): List<String> {
        val n = word.length
        if (words.isEmpty() || n < 3 || n > MAX_CORRECT_LENGTH) return emptyList()
        // Kata yang sudah benar tidak perlu dikoreksi.
        if (isKnownWord(word) || (useLearned && LearnedWords.countOf(word) >= MIN_COUNT_UNKNOWN)) {
            return emptyList()
        }

        val found = LinkedHashSet<String>()
        fun consider(c: String) {
            if (c == word || c.length < 2) return
            if (isKnownWord(c) || (useLearned && LearnedWords.countOf(c) >= MIN_COUNT_UNKNOWN)) found.add(c)
        }

        for (i in 0 until n) consider(word.removeRange(i, i + 1))
        for (i in 0 until n - 1) {
            val chars = word.toCharArray()
            val t = chars[i]
            chars[i] = chars[i + 1]
            chars[i + 1] = t
            consider(String(chars))
        }
        for (i in 0 until n) {
            for (c in 'a'..'z') {
                if (c != word[i]) consider(word.substring(0, i) + c + word.substring(i + 1))
            }
        }
        for (i in 0..n) {
            for (c in 'a'..'z') consider(word.substring(0, i) + c + word.substring(i))
        }

        fun penalty(c: String): Int {
            var p = 0
            if (c[0] != word[0]) p += 10
            if (c.length != n) p += 1
            if (useLearned) p -= minOf(LearnedWords.countOf(c), 20)
            return p
        }
        return found.sortedWith(compareBy<String>({ penalty(it) }, { it })).take(limit)
    }

    // ----------------------------------------------------------- 3. Tebakan kata berikutnya

    /**
     * Sampai [limit] kata yang biasa mengikuti [prev] (huruf kecil): dari pasangan yang dipelajari
     * dari ketikan lebih dulu, lalu dari data awal.
     */
    fun predictNext(prev: String, limit: Int = 3, useLearned: Boolean = true): List<String> {
        if (prev.isEmpty()) return emptyList()
        val result = ArrayList<String>(limit)

        if (useLearned) {
            val prevKnown = isKnownWord(prev)
            LearnedWords.nextFor(prev)
                .filter { it.second >= MIN_COUNT_UNKNOWN || (prevKnown && isKnownWord(it.first)) }
                .sortedByDescending { it.second }
                .forEach { if (result.size < limit && it.first !in result) result.add(it.first) }
        }
        val seed = seedPairs[prev]
        if (seed != null) {
            for (w in seed) {
                if (result.size >= limit) break
                if (w !in result) result.add(w)
            }
        }
        return result
    }

    // --------------------------------------------------------------------------- Bantuan

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
        const val WORDS_PATH = "words_id.txt"
        const val PAIRS_PATH = "bigrams_id.txt"
        const val MIN_COUNT_UNKNOWN = 2
        const val MAX_CORRECT_LENGTH = 16
    }
}
