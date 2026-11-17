package com.zeku.keyboard

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Satu screenshot di papan klip. [pinned] = disematkan: tetap tampil walau sudah lewat batas waktu. */
class ShotItem(
    val id: Long,
    val uri: Uri,
    val dateSec: Long,
    val mime: String,
    var pinned: Boolean
)

/**
 * Screenshot terbaru dari galeri untuk papan klip.
 *
 * Yang dibaca hanya daftar screenshot (lewat MediaStore, butuh izin akses foto); file aslinya tidak
 * pernah diubah atau dihapus. "Hapus" di papan klip hanya menyembunyikan screenshot itu dari daftar.
 * Sematan dan daftar sembunyi disimpan di folder khusus yang tidak ikut pencadangan otomatis.
 * Data di memori hanya disentuh di thread utama; pencarian di MediaStore berjalan di latar belakang.
 */
object ScreenshotStore {

    private const val FILE_NAME = "screenshot_state.json"
    private const val MAX_RECENT = 40
    private const val DISMISSED_KEEP_SEC = 60L * 86_400L
    private const val SAVE_DELAY_MS = 1500L

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val saveTask = Runnable { saveNow() }

    private var stateLoaded = false
    private val pinned = LinkedHashMap<Long, Long>() // id -> waktu disematkan (ms)
    private val dismissed = HashMap<Long, Long>() // id -> tanggal ditambahkan (detik)
    private var dirty = false
    private var appContext: Context? = null

    private class QueryResult(val items: List<ShotItem>, val foundPinned: Set<Long>, val ok: Boolean)

    /** Izin yang dibutuhkan untuk membaca daftar screenshot, sesuai versi Android. */
    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES"
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)

    /** Baca sematan dan daftar sembunyi (file kecil), sekali saja. */
    private fun ensureState(context: Context) {
        appContext = context.applicationContext
        if (stateLoaded) return
        stateLoaded = true
        try {
            val f = file(context)
            if (!f.exists()) return
            val json = JSONObject(f.readText())
            val pins = json.optJSONArray("pinned") ?: JSONArray()
            for (i in 0 until pins.length()) {
                val o = pins.optJSONObject(i) ?: continue
                pinned[o.optLong("id")] = o.optLong("at", 0L)
            }
            val hidden = json.optJSONArray("dismissed") ?: JSONArray()
            for (i in 0 until hidden.length()) {
                val o = hidden.optJSONObject(i) ?: continue
                dismissed[o.optLong("id")] = o.optLong("d", 0L)
            }
        } catch (e: Exception) {
            // File rusak: mulai dari kosong.
        }
    }

    /**
     * Cari screenshot yang ditambahkan sejak [windowStartSec] (detik), terbaru di depan, lalu tambahkan
     * yang disematkan walau lebih lama. Hasil dikirim ke [onResult] di thread utama.
     */
    fun load(context: Context, windowStartSec: Long, onResult: (List<ShotItem>) -> Unit) {
        val app = context.applicationContext
        ensureState(app)
        val pinnedSnapshot = LinkedHashMap(pinned)
        val dismissedIds = HashSet(dismissed.keys)
        io.execute {
            val result = query(app, windowStartSec, pinnedSnapshot.keys.toList(), dismissedIds)
            handler.post {
                if (result.ok) {
                    // Sematan yang file-nya sudah tidak ada di galeri dibuang dari daftar sematan.
                    val gone = pinnedSnapshot.keys.filter { it !in result.foundPinned }
                    var changed = false
                    for (id in gone) if (pinned.remove(id) != null) changed = true
                    val cutoff = System.currentTimeMillis() / 1000L - DISMISSED_KEEP_SEC
                    if (dismissed.entries.removeAll { it.value in 1L until cutoff }) changed = true
                    if (changed) markDirty()
                }
                onResult(order(result.items))
            }
        }
    }

    private fun query(
        context: Context,
        windowStartSec: Long,
        pinnedIds: List<Long>,
        dismissedIds: Set<Long>
    ): QueryResult {
        val items = ArrayList<ShotItem>()
        val seen = HashSet<Long>()
        val foundPinned = HashSet<Long>()
        val pinnedSet = pinnedIds.toHashSet()
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE
        )
        var ok = true
        try {
            // Screenshot terbaru: nama folder atau nama file memuat "screenshot".
            val pathColumn = pathColumnName()
            val selection = "($pathColumn LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?) " +
                "AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val args = arrayOf("%creenshot%", "Screenshot%", windowStartSec.toString())
            val bundle = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(
                    ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_RECENT)
            }
            resolver.query(collection, projection, bundle, null)?.use { c ->
                while (c.moveToNext()) {
                    val item = readItem(c, collection, pinnedSet)
                    if (item.id in dismissedIds || !seen.add(item.id)) continue
                    if (item.pinned) foundPinned.add(item.id)
                    items.add(item)
                }
            }

            // Yang disematkan: tetap diambil walau lebih lama dari batas waktu.
            val missing = pinnedIds.filter { it !in seen }
            for (chunk in missing.chunked(50)) {
                val marks = chunk.joinToString(",") { "?" }
                val bundle2 = Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.Images.Media._ID} IN ($marks)"
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        chunk.map { it.toString() }.toTypedArray()
                    )
                }
                resolver.query(collection, projection, bundle2, null)?.use { c ->
                    while (c.moveToNext()) {
                        val item = readItem(c, collection, pinnedSet)
                        if (!seen.add(item.id)) continue
                        foundPinned.add(item.id)
                        items.add(item)
                    }
                }
            }
        } catch (e: Exception) {
            ok = false
        }
        return QueryResult(items, foundPinned, ok)
    }

    /** Kolom lokasi file: RELATIVE_PATH di Android 10 ke atas, DATA di versi lama. */
    @Suppress("DEPRECATION")
    private fun pathColumnName(): String =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH
        else MediaStore.Images.Media.DATA

    private fun readItem(c: Cursor, collection: Uri, pinnedSet: Set<Long>): ShotItem {
        val id = c.getLong(0)
        val date = c.getLong(1)
        val mime = c.getString(2) ?: "image/png"
        return ShotItem(id, ContentUris.withAppendedId(collection, id), date, mime, id in pinnedSet)
    }

    /** Urutan tampil: yang disematkan dulu (paling baru disematkan di depan), lalu sisanya terbaru di depan. */
    fun order(items: List<ShotItem>): List<ShotItem> {
        for (item in items) item.pinned = item.id in pinned
        return items
            .filter { it.id !in dismissed }
            .sortedWith(
                compareByDescending<ShotItem> { it.pinned }
                    .thenByDescending { if (it.pinned) pinned[it.id] ?: 0L else it.dateSec }
            )
    }

    /** Sematkan atau lepas sematan [ids]. */
    fun setPinned(context: Context, ids: Set<Long>, pin: Boolean) {
        ensureState(context)
        val now = System.currentTimeMillis()
        for (id in ids) {
            if (pin) pinned[id] = now else pinned.remove(id)
        }
        markDirty()
    }

    /** Sembunyikan dari daftar (file aslinya tidak dihapus). Sematannya ikut dilepas. */
    fun dismiss(context: Context, items: Collection<ShotItem>) {
        ensureState(context)
        for (item in items) {
            pinned.remove(item.id)
            dismissed[item.id] = item.dateSec
        }
        markDirty()
    }

    private fun markDirty() {
        dirty = true
        handler.removeCallbacks(saveTask)
        handler.postDelayed(saveTask, SAVE_DELAY_MS)
    }

    fun flush() {
        handler.removeCallbacks(saveTask)
        if (dirty) saveNow()
    }

    private fun saveNow() {
        val app = appContext ?: return
        dirty = false
        val pins = JSONArray()
        for ((id, at) in pinned) pins.put(JSONObject().put("id", id).put("at", at))
        val hidden = JSONArray()
        for ((id, d) in dismissed) hidden.put(JSONObject().put("id", id).put("d", d))
        val text = JSONObject().put("pinned", pins).put("dismissed", hidden).toString()
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
                // Gagal menyimpan: dicoba lagi pada perubahan berikutnya.
            }
        }
    }
}

/** Gambar kecil screenshot untuk kisi di papan klip, dimuat di latar belakang dan disimpan sementara. */
object ShotThumbs {

    private const val TARGET_WIDTH = 300
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val loading = HashSet<Long>() // hanya disentuh di thread utama
    private val failed = HashSet<Long>() // gambar yang gagal dibaca: tidak dicoba terus-menerus
    private val cache = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    fun get(id: Long): Bitmap? = cache.get(id)

    /** Muat gambar kecil [item] bila belum ada; [onReady] dipanggil di thread utama setelah siap. */
    fun request(context: Context, item: ShotItem, onReady: () -> Unit) {
        if (cache.get(item.id) != null || item.id in failed || !loading.add(item.id)) return
        val resolver = context.applicationContext.contentResolver
        executor.execute {
            var bitmap: Bitmap? = null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                resolver.openInputStream(item.uri)?.use { bitmap = BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Exception) {
                // Gambar tidak terbaca: kotak tetap kosong.
            }
            handler.post {
                loading.remove(item.id)
                val result = bitmap
                if (result != null) cache.put(item.id, result) else failed.add(item.id)
                onReady()
            }
        }
    }
}
