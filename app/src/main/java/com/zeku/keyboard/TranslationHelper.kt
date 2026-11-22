package com.zeku.keyboard

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * Terjemahan di perangkat memakai ML Kit. Data bahasa (sekitar 30 MB per bahasa) tidak diunduh
 * otomatis: pengguna mengunduhnya sendiri dari layar Paket Unduhan, dan terjemahan hanya jalan
 * bila semua data yang dibutuhkan sudah ada. Teks yang diterjemahkan tidak dikirim ke server.
 * Semua panggilan balik berjalan di thread utama.
 */
object TranslationHelper {

    const val ENGLISH = "en"
    const val STATUS_OK = 0
    const val STATUS_MISSING = 1
    const val STATUS_ERROR = 2

    /** Hasil terjemahan, atau daftar bahasa yang datanya belum diunduh ([missing]). */
    class Result(val status: Int, val text: String = "", val missing: List<String> = emptyList())

    /** Semua bahasa yang didukung ML Kit Translate (59 bahasa). */
    private val ALL = listOf(
        "af", "sq", "ar", "be", "bn", "bg", "ca", "zh", "hr", "cs", "da", "nl", "en", "eo", "et",
        "fi", "fr", "gl", "ka", "de", "el", "gu", "ht", "he", "hi", "hu", "is", "id", "ga", "it",
        "ja", "kn", "ko", "lv", "lt", "mk", "ms", "mt", "mr", "no", "fa", "pl", "pt", "ro", "ru",
        "sk", "sl", "es", "sw", "sv", "tl", "ta", "te", "th", "tr", "uk", "ur", "vi", "cy"
    )

    private val indonesian = Locale.forLanguageTag("id")
    private val names = HashMap<String, String>()
    private var sorted: List<String>? = null

    /** Nama bahasa dalam bahasa Indonesia, misalnya "Inggris". */
    fun languageName(code: String): String = names.getOrPut(code) {
        val n = Locale.forLanguageTag(code).getDisplayLanguage(indonesian)
        if (n.isBlank()) code.uppercase() else n.replaceFirstChar { it.titlecase(indonesian) }
    }

    /** Daftar bahasa: Indonesia dan Inggris dulu, sisanya urut abjad menurut namanya. */
    fun languages(): List<String> {
        sorted?.let { return it }
        val rest = ALL.filter { it != "id" && it != ENGLISH }.sortedBy { languageName(it).lowercase(indonesian) }
        val list = listOf("id", ENGLISH) + rest
        sorted = list
        return list
    }

    /** Data bahasa yang dibutuhkan: asal, tujuan, dan Inggris sebagai perantara bila keduanya bukan Inggris. */
    fun requiredModels(from: String, to: String): List<String> {
        val list = LinkedHashSet<String>()
        list.add(from)
        list.add(to)
        if (from != ENGLISH && to != ENGLISH) list.add(ENGLISH)
        return list.toList()
    }

    /** Bahasa yang datanya sudah ada di perangkat. Inggris bawaan, selalu dianggap ada. */
    fun downloadedModels(onResult: (Set<String>) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models -> onResult(models.map { it.language }.toSet() + ENGLISH) }
            .addOnFailureListener { onResult(setOf(ENGLISH)) }
    }

    /** Unduh data bahasa [code]. [wifiOnly] menunda unduhan sampai tersambung Wi-Fi. */
    fun download(code: String, wifiOnly: Boolean, onDone: (Boolean) -> Unit) {
        val builder = DownloadConditions.Builder()
        if (wifiOnly) builder.requireWifi()
        RemoteModelManager.getInstance()
            .download(TranslateRemoteModel.Builder(code).build(), builder.build())
            .addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    fun delete(code: String, onDone: (Boolean) -> Unit) {
        RemoteModelManager.getInstance()
            .deleteDownloadedModel(TranslateRemoteModel.Builder(code).build())
            .addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    /** Terjemahkan [text] dari [from] ke [to]. Tidak mengunduh apa pun: data yang kurang dilaporkan. */
    fun translate(text: String, from: String, to: String, onResult: (Result) -> Unit) {
        downloadedModels { have ->
            val missing = requiredModels(from, to).filter { it !in have }
            if (missing.isNotEmpty()) {
                onResult(Result(STATUS_MISSING, missing = missing))
                return@downloadedModels
            }
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(from)
                .setTargetLanguage(to)
                .build()
            val translator = Translation.getClient(options)
            translator.translate(text)
                .addOnSuccessListener { out ->
                    translator.close()
                    onResult(Result(STATUS_OK, text = out))
                }
                .addOnFailureListener {
                    translator.close()
                    onResult(Result(STATUS_ERROR))
                }
        }
    }
}
