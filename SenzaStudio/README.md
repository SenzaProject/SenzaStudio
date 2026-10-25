# Senza Studio
IDE Android mini di HP: tulis kode Java/XML → preview UI → build → tanda tangan → install, semuanya di perangkat.
Dibuat oleh **SenzaProject**.

## Fitur
- **Code Editor** — Java & XML, syntax highlight, nomor baris, tab file, undo/redo, cari & ganti, tombol simbol.
- **UI Preview Live** — merender `res/layout/*.xml` langsung (Design) atau menampilkan sumbernya (Code); pilih layout & ukuran perangkat.
- **Project Explorer** — pohon file nyata: tambah kelas/layout/values/folder/file, ganti nama, hapus, cari file.
- **Build APK** — Debug/Release, App Name, Version Code/Name, log real-time, Install / Share / simpan ke Download, tombol Batal.
- **Dependency Maven (Fase 1)** — tulis library di `build.gradle` (mis. `implementation 'androidx.appcompat:appcompat:1.6.1'`), diunduh dari Google Maven & Maven Central beserta dependency turunannya, AAR diekstrak otomatis.
- **Manifest merger** — izin, activity, service, provider, meta-data dari library digabung ke manifest aplikasi.
- **Build bertahap + cache** — resource, Java, dan dex dilewati bila tidak berubah; dex tiap library disimpan di cache bersama semua proyek.
- **Multi-dex** — lebih dari 65 ribu method otomatis dipecah; lambda dan method reference di-desugar oleh D8.
- **Build di foreground service** — build tetap jalan saat aplikasi ditinggal, dengan notifikasi progres dan tombol Batal.
- **Struktur lengkap** — `assets/`, `res/raw|mipmap|xml`, `jniLibs/<abi>/`, `libs/*.jar`, folder sumber `src/` dan `java/`.
- **Gradle-lite** — membaca `namespace`, `applicationId`, `versionCode`, `versionName`, `minSdk`, `targetSdk`, dan daftar `dependencies`. Impor `.zip` proyek Gradle standar (`app/src/main/...`) otomatis disusun ulang.
- **Kotlin di perangkat (Fase 2)** — file `.kt` dikompilasi oleh compiler Kotlin 1.9.24 yang berjalan di **proses terpisah** (`app_process`); kalau memori habis hanya proses itu yang mati. Campuran Java + Kotlin didukung. `kotlin-stdlib` ditambahkan otomatis. Memori compiler diatur di Settings > Alat build.
- **R8 / ProGuard (Fase 2)** — Build **Release** punya opsi *Perkecil & acak kode*. Aturan milikmu ada di `proguard-rules.pro` (dibuat otomatis), aturan library (`proguard.txt` di AAR) dan aturan hasil aapt2 ikut dipakai. `out/mapping.txt` dibuat untuk membaca stack trace yang diacak.
- **Terminal** — perintah shell Android di folder proyek + `build`, `assembleDebug`, `assembleRelease`.
- **File Manager** — jelajah, ganti nama, hapus, bagikan; impor & ekspor proyek `.zip`.
- **About & Settings** — info aplikasi dan pengembang (SenzaProject), ukuran font, nomor baris, simpan otomatis.

- **Editor pintar (Fase 3)** — autocomplete (kata kunci, kelas/method proyek, `R.id.*`, `@string/*`, atribut & widget XML, import otomatis untuk kelas Android umum), diagnostik langsung (kurung/kutip tidak seimbang, XML tidak valid, `R.*`/`@*` tak ditemukan, `layout_width` kosong, Activity di manifest tanpa kelas), lompat ke definisi, cari penggunaan, auto-indent, kurung berpasangan, pencocokan kurung, lipat kode, dan format otomatis.
- **Cari & ganti seluruh proyek, rename simbol (Fase 3)** — dengan opsi huruf besar/kecil, kata utuh, regex. Rename juga mengganti nama file kelas.
- **Aset, ikon, manifest, template (Fase 3)** — impor gambar/font/audio ke folder yang tepat, pembuat ikon (mipmap 5 ukuran + adaptive icon), editor manifest visual (izin, activity, peluncur, nama aplikasi), 4 template proyek (Kosong, RecyclerView, Multi-layar, Navigasi tab).
- **UI Preview lebih lengkap & split view (Fase 3)** — ConstraintLayout (perkiraan), RecyclerView/ListView contoh, CardView, Toolbar, `<include>`, tema terang/gelap, potret/lanskap; split view menampilkan preview berdampingan dengan editor dan ikut berubah saat mengetik.
- **Logcat, crash viewer, Run (Fase 3)** — Logcat langsung dengan filter; crash viewer yang bisa melompat ke baris kode proyek; tombol Run membangun, memasang, lalu membuka aplikasi.

- **Rilis & kolaborasi (Fase 4)** —
  - **Keystore manager**: buat keystore RSA 2048 (PKCS12) atau impor `.p12`/`.pfx`/BKS, lihat sidik jari SHA-256/SHA-1, ekspor cadangan, hapus. Kata sandi disimpan terenkripsi (AES-GCM, kunci di Android Keystore). Build Release memakai keystore yang dipilih di layar Build; APK diverifikasi otomatis setelah ditandatangani (v1+v2+v3).
  - **PackageInstaller + zipalign**: Install memakai sesi PackageInstaller (hasil dan alasan gagal dilaporkan, mis. tanda tangan beda); jika gagal mundur ke cara lama. Entri tanpa kompresi diselaraskan (zipalign) saat APK dirakit, sebelum ditandatangani.
  - **Git (JGit)**: init, clone, status, commit, push, pull, cabang, riwayat. HTTPS + token (GitHub PAT), token terenkripsi. `out/` otomatis diabaikan.
  - **Folder publik (SAF)**: pilih folder di penyimpanan publik/SD/Drive, lalu *Simpan ke folder* atau *Ambil dari folder*. Proyek kerja tetap di penyimpanan privat; folder itu cermin (file tidak pernah dihapus otomatis).
  - **Build variants + BuildConfig**: `buildTypes`, `productFlavors` (satu flavor per build), `applicationIdSuffix`, `versionNameSuffix`, `buildConfigField` dibaca dari build.gradle. `BuildConfig.java` (DEBUG, APPLICATION_ID, BUILD_TYPE, FLAVOR, VERSION_CODE, VERSION_NAME + field kustom) dibuat tiap build.
  - **Bundel AAB (eksperimental)**: Release + saklar *Buat juga bundel .aab*. Dirakit tanpa bundletool.

## Batasan (jujur)
- **Fase 4 belum diuji di perangkat** (ditulis tanpa bisa menjalankan aplikasinya). Uji satu per satu dan kirim log bila gagal.
- **AAB**: format dirakit sendiri (aapt2 `--proto-format` + susunan modul `base` + tanda tangan gaya jarsigner, `BundleConfig.pb` kosong). Belum divalidasi lawan bundletool/Play Console. Jalankan `bundletool validate --bundle=app-release.aab` di PC sebelum mengunggah.
- **Git**: hanya HTTPS + token, tanpa SSH. Repo bergaya Gradle (`app/src/main/...`) tidak disusun ulang saat clone; clone di PC lalu Impor .zip. JGit 5.x dipakai karena 6.x butuh Java 11.
- **Keystore**: `.jks` bawaan Java tidak didukung Android; ubah ke PKCS12 (`keytool -importkeystore ... -deststoretype PKCS12`). Hilang keystore = update aplikasi yang sudah dirilis tidak bisa dibuat lagi: ekspor cadangannya.
- **Flavor**: tanpa `dimension` (satu flavor per build); `resValue` belum didukung; flavor tidak punya source set sendiri (`src/free/...`).
- **Folder publik**: bukan lokasi kerja langsung (builder butuh akses file biasa); sinkron dua arah manual.
- Yang dibangun: kode **Java** dan **Kotlin** + resource XML memakai `android.jar`, plus library Maven/AAR.
- **Kotlin** (Fase 2): build Kotlin pertama lambat dan butuh RAM besar. Compiler Kotlin dipakai di proses terpisah lewat `app_process`; belum diuji di semua perangkat. Library yang dibuat dengan Kotlin lebih baru dari 1.9.x bisa ditolak compiler. Jetpack Compose belum didukung (butuh compiler plugin Compose).
- **R8**: aplikasi hasil R8 perlu diuji. Kelas yang dipakai lewat refleksi harus kamu `-keep` di `proguard-rules.pro`; pesan `Missing class` diberi saran `-dontwarn`.
- Gradle-lite hanya membaca subset sederhana. BOM/`platform(...)`, `project(...)`, version catalog (`libs.xxx`), dan plugin Gradle tidak didukung (diberi peringatan di log).
- Library yang butuh annotation processing (Room, Hilt, Glide compiler, `kapt`/`ksp`) belum bisa dipakai. Baris `annotationProcessor`/`kapt`/`ksp` diberi peringatan di log.
- Desugaring API Java baru untuk HP lama (core library desugaring) belum ada; `minSdk` dipaksa minimal 21.
- Unduhan dependency butuh internet dan ruang penyimpanan (menu proyek > Bersihkan cache build).
- Build butuh **aapt2 ARM64 (Android, berdiri sendiri)** di `app/src/main/jniLibs/arm64-v8a/libaapt2.so`.
- UI Preview adalah perkiraan; widget yang belum dikenal ditandai ⚠.
- Release tanpa keystore pilihan tetap memakai keystore bawaan (hanya uji coba). Pilih keystore sendiri untuk rilis.
- **Fase 3**: autocomplete dan diagnostik berbasis pola teks (bukan compiler), jadi bisa meleset: tidak ada pengetahuan tipe variabel dan tidak mendeteksi semua kesalahan. Garis merah hanya untuk kesalahan struktur yang pasti. Peringatan `R.*` bisa keliru untuk resource dari library. Lipat kode mengunci pengetikan sampai lipatan dibuka. Preview ConstraintLayout hanya perkiraan (chain, guideline, barrier belum didukung). Logcat aplikasi lain butuh izin `READ_LOGS` sekali lewat adb: `adb shell pm grant com.senza.studio android.permission.READ_LOGS` (Termux + Wireless debugging juga bisa). Tanpa itu, pakai tab Crash lalu tempel stack trace. Run tetap melewati dialog pasang bawaan Android, lalu aplikasi dibuka otomatis saat kamu kembali ke Senza Studio. Desainer drag-and-drop belum ada.

## Sebelum push
1. Taruh `libaapt2.so` di folder di atas.
2. Push ke GitHub (branch `main`). Workflow `Build APK` jalan otomatis.
3. Unduh artifact `SenzaStudio-debug` dari tab Actions, install di HP.

## Push dari Termux
    pkg install git
    cd SenzaStudio
    git init && git add . && git commit -m "Senza Studio v1.3.0: keystore, git, varian, AAB (Fase 4)"
    git branch -M main
    git remote add origin https://github.com/SenzaProject/SenzaStudio.git
    read -s -p "Token: " T; echo
    git push https://SenzaProject:$T@github.com/SenzaProject/SenzaStudio.git main

Kalau repo sudah ada dan terhubung (update berikutnya), cukup:

    cd SenzaStudio
    git add . && git commit -m "Fase 3: editor pintar dan alat produktivitas"
    read -s -p "Token: " T; echo
    git push https://SenzaProject:$T@github.com/SenzaProject/SenzaStudio.git main

## Alur build di dalam aplikasi
Maven resolver → manifest merger → aapt2 compile (app + library) → aapt2 link → kotlinc (proses terpisah, bila ada `.kt`) → ECJ → D8 (per library, di-cache) → gabung dex **atau** R8 (release + Perkecil kode) → rakit APK → apksig (verifikasi) → [AAB] → PackageInstaller

## Struktur kode (`com.senza.studio`)
`MainActivity` (navigasi, drawer, build) · `*Screen.kt` (tiap layar) · `LayoutPreview` (renderer XML) ·
`SenzaBuilder` (pipeline build) · `KotlinSupport` + `KotlinCompilerMain` (compiler Kotlin di proses terpisah) · `MavenResolver` (unduh & selesaikan dependency) · `ManifestMerger` · `GradleLite` (baca build.gradle) ·
`BuildService` (foreground service + notifikasi) · `ProjectStore` (proyek, zip) · `KeystoreManager`/`KeystoreScreen` · `GitTools`/`GitScreen` · `FolderSync` · `Variants` · `AabBuilder` · `ApkTools`/`Installer` · `SecretStore` · `Theme` (tema Liquid Glass)
