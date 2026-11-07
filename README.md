# ZekuBoard

Aplikasi keyboard Android (Kotlin). Tahap ini baru bagian aplikasinya:
Beranda, Tema (Gelap / Terang / Ikut sistem), dan Pengaturan.
Keyboard (QWERTY) dan fitur translate menyusul di tahap berikutnya.

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

## Struktur singkat

- `app/src/main/java/com/zeku/keyboard/` — kode Kotlin
  (`MainActivity`, `SettingsActivity`, `Prefs`, `ZekuApp`, `SystemBars`)
- `app/src/main/res/` — layout, tema, warna, ikon
- `.github/workflows/build.yml` — build APK otomatis
