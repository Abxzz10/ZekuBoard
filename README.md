# ZekuBoard

Aplikasi keyboard Android (Kotlin). Tahap ini: aplikasi (Beranda, Tema, Pengaturan)
dan keyboard QWERTY hitam putih (huruf, simbol, portrait + landscape).
Fitur translate dan fitur tambahan keyboard menyusul di tahap berikutnya.

- Package: `com.zeku.keyboard`
- minSdk 26 (Android 8.0), targetSdk 34
- Build otomatis lewat GitHub Actions (`.github/workflows/build.yml`)

## Cara mengunggah ke GitHub (lewat browser)

1. Buat repo kosong di GitHub (tanpa README).
2. Di halaman repo, pilih **uploading an existing file**.
3. Ekstrak zip proyek, lalu seret **seluruh isi folder `ZekuBoard`** (bukan folder
   induknya) ke halaman unggah. Pastikan folder `app`, file `build.gradle.kts`,
   `settings.gradle.kts`, dan folder `.github` ikut terunggah.
4. Klik **Commit changes** ke branch `main`.

Kalau folder `.github` tidak ikut terunggah (folder tersembunyi), buat manual:
**Add file → Create new file**, isi nama `.github/workflows/build.yml`, lalu
tempel isi file `build.yml` dari proyek ini.

## Mengambil APK

1. Buka tab **Actions** di repo. Build berjalan otomatis setiap ada push.
2. Tunggu sampai tanda centang hijau (sekitar 3 sampai 6 menit).
3. Buka run terbaru, lalu unduh **ZekuBoard-debug-apk** di bagian *Artifacts*.
4. Ekstrak zip-nya dan pasang `app-debug.apk` di HP.
   (Izinkan pemasangan dari sumber tidak dikenal bila diminta.)

Build bisa dijalankan ulang kapan saja lewat **Actions → Build APK → Run workflow**.

## Mengaktifkan keyboard

Buka aplikasi ZekuBoard → Beranda → ikuti dua langkah (aktifkan, lalu pilih
sebagai keyboard). Pengaturan getar, suara, tinggi keyboard, dan tema langsung
dipakai keyboard saat kolom teks dibuka lagi.

## Struktur singkat

- `app/src/main/java/com/zeku/keyboard/` — kode Kotlin
  - Aplikasi: `MainActivity`, `SettingsActivity`, `Prefs`, `ZekuApp`, `SystemBars`
  - Keyboard: `ZekuIME` (layanan keyboard), `ZekuKeyboardView` (tampilan + sentuhan),
    `KeyboardLayouts` (tata letak huruf dan simbol)
- `app/src/main/res/` — layout, tema, warna, ikon (`ic_kb_*` = ikon tombol keyboard)
- `app/src/main/res/xml/method.xml` — konfigurasi keyboard (input method)
- `.github/workflows/build.yml` — build APK otomatis
