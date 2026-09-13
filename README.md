# 📱 GameTrans Android - Aplikasi Android Penerjemah Teks Game Real-time

**GameTrans Android** adalah aplikasi Android native (Kotlin + Jetpack Compose) yang dirancang khusus untuk para gamer di Android. Aplikasi ini dapat menerjemahkan teks dialog game yang tidak memiliki bahasa Indonesia (seperti game JRPG, Visual Novel, Gacha Jepang/Inggris/Mandarin/Korea, atau game emulator PPSSPP/AetherSX2/Switch) secara langsung saat game sedang dimainkan!

Aplikasi ini menggunakan sistem **Floating Bubble (Gelembung Melayang)** yang berada di sisi layar HP. Tanpa perlu keluar dari game, Anda cukup mengetuk gelembung melayang tersebut untuk membaca teks layar dan memunculkan terjemahan Bahasa Indonesia secara instan!

---

## ✨ Fitur Unggulan

- **Tanpa Butuh Akses Root (No Root Required)**:
  Menggunakan API resmi Android `MediaProjection` untuk menangkap layar game dan `SYSTEM_ALERT_WINDOW` untuk overlay jendela melayang.
- **Gelembung Melayang Fleksibel (Draggable Floating Bubble)**:
  Bisa digeser ke sisi mana pun pada layar HP Anda agar tidak menutupi tombol kontrol game.
- **On-Device OCR Cepat (Google ML Kit Text Recognition)**:
  Mengenali teks:
  - **Jepang (Kanji, Hiragana, Katakana)**
  - **Inggris & Bahasa Latin**
  - **Mandarin (Aksara Hanzi)**
  - **Korea (Hangul)**
- **Floating HUD Subtitle (Subtitle Melayang Bergaya Game)**:
  Menampilkan hasil terjemahan dalam kotak subtitle hitam transparan yang elegan dengan teks warna kuning kontras tinggi, sangat mudah dibaca saat bermain game.
- **Audio Text-to-Speech (TTS)**:
  Tombol speaker untuk mendengarkan pengucapan dialog.
- **Tombol Salin Cepat (Copy to Clipboard)**:
  Untuk menyalin teks terjemahan jika dibutuhkan.
- **Sistem Cache Dialog Otomatis**:
  Percakapan atau menu yang sering muncul berulang (seperti *Attack, Item, Quest, Yes, No*) akan langsung tampil seketika (0ms) tanpa memakan kuota internet.

---

## 🛠️ Arsitektur & Struktur Proyek

```text
game-translator-android/
├── app/
│   ├── build.gradle.kts                         # Dependensi Compose, ML Kit OCR, Material 3
│   └── src/main/
│       ├── AndroidManifest.xml                  # Izin Overlay, Foreground Service, MediaProjection
│       ├── java/com/gametrans/app/
│       │   ├── MainActivity.kt                  # UI Jetpack Compose untuk konfigurasi & aktivasi
│       │   ├── service/
│       │   │   ├── FloatingBubbleService.kt     # Foreground Service untuk tombol gelembung melayang
│       │   │   └── ScreenCaptureManager.kt      # MediaProjection screen capture engine
│       │   ├── ocr/
│       │   │   └── GameOcrManager.kt            # Google ML Kit Text Recognition (JP, EN, ZH, KO)
│       │   ├── translation/
│       │   │   ├── TranslationManager.kt        # Multi-engine translation (Online + Offline ML Kit)
│       │   │   └── TranslationCache.kt          # Penyimpanan cache dialog lokal
│       │   └── ui/theme/                        # Cyberpunk Dark Gaming Theme
│       └── res/
│           ├── layout/                          # Layout XML untuk gelembung dan dialog HUD
│           │   ├── layout_floating_bubble.xml
│           │   ├── layout_translation_dialog.xml
│           │   └── layout_crop_overlay.xml
│           ├── drawable/                        # Vektor ikon game, gelembung, speaker, salin
│           └── values/                          # Strings, colors, dan themes
├── build.gradle.kts                             # Top-level build script
├── settings.gradle.kts                          # Module and repository settings
├── gradle.properties
└── README.md                                    # Petunjuk lengkap
```

---

## 🚀 Cara Membuka & Membuat APK (Build APK)

### Langkah 1: Buka di Android Studio
1. Buka aplikasi **Android Studio** di komputer/laptop Anda.
2. Pilih menu **File** -> **Open...**
3. Cari dan pilih folder:
   `/home/xin/.gemini/antigravity/scratch/game-translator-android`
4. Tunggu beberapa saat hingga Android Studio selesai melakukan **Gradle Sync**.

### Langkah 2: Build APK Langsung
- **Cara Otomatis via Android Studio:**
  1. Klik menu **Build** di toolbar atas.
  2. Pilih **Build Bundle(s) / APK(s)** -> **Build APK(s)**.
  3. Setelah selesai, klik link pop-up **"locate"** untuk membuka folder tempat file `app-debug.apk` berada.

- **Cara via Command Line / Terminal:**
  Jalankan perintah ini di terminal:
  ```bash
  cd /home/xin/.gemini/antigravity/scratch/game-translator-android
  ./gradlew assembleDebug
  ```
  File APK siap pakai akan otomatis dibuat di:
  `app/build/outputs/apk/debug/app-debug.apk`

---

## 📲 Cara Memasang (Install) di HP Android

1. Pindahkan file `app-debug.apk` ke HP Android Anda (bisa lewat kabel USB, Google Drive, WhatsApp, atau via ADB: `adb install app-debug.apk`).
2. Di HP Android Anda, buka file manager dan ketuk file `app-debug.apk` untuk menginstalnya.
   *(Jika muncul peringatan "Install unknown apps", pilih Izinkan / Allow).*

---

## 🎮 Panduan Menggunakan Saat Bermain Game

1. **Buka Aplikasi GameTrans**:
   - Di tampilan awal, jika muncul kartu merah peringatan izin, klik **"Beri Izin"** untuk mengaktifkan izin *"Display over other apps"* (Tampilkan di atas aplikasi lain).
2. **Pilih Bahasa Game**:
   - Pilih bahasa asal game Anda: **Jepang (JA)**, **Inggris (EN)**, **Mandarin (ZH)**, atau **Korea (KO)**.
   - Bahasa tujuan sudah disetel otomatis ke **Bahasa Indonesia**.
3. **Klik Tombol "Mulai Layanan Gelembung Melayang"**:
   - Sistem Android akan memunculkan konfirmasi izin perekaman layar (*Start recording/casting*). Klik **"Start now"** / **"Mulai sekarang"**.
   - Ikon gelembung biru melayang akan langsung muncul di tepi layar HP Anda.
4. **Buka Game Anda**:
   - Jalankan game apa saja yang ingin Anda mainkan.
   - Anda bebas menyeret (*drag*) gelembung melayang ke posisi manapun yang nyaman dan tidak menutupi kontrol game.
5. **Terjemahkan Dialog Game**:
   - Saat muncul dialog percakapan atau menu yang ingin diterjemahkan, cukup **ketuk sekali ikon gelembung tersebut**.
   - GameTrans akan memotret layar, membaca tulisan game dengan AI OCR, dan memunculkan kotak subtitle terjemahan Bahasa Indonesia di bagian bawah layar!
   - Tekan tombol speaker untuk mendengarkan suara, atau tombol salin untuk menyalin teks.
