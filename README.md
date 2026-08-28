# TradingView AI - AnalysPasar

Analisis pasar kripto real-time dengan sinyal AI on-device, analitik indikator teknis, dan mesin harga kripto presisi.

## Fitur Utama
- **Real-time Streaming**: Data pasar dari Indodax via WebSocket/REST V2.
- **AI Signal Engine**: Integrasi Gemini AI untuk analisis sentimen dan narasi pasar berdasarkan headline terbaru.
- **Technical Analytics**: Evaluator strategi (ScalpingMtf, SecondWave, Swing) untuk membantu pengambilan keputusan.
- **Secure Trading**: Eksekusi order riil di Indodax dengan pengamanan PIN dan enkripsi kredensial API.
- **Portfolio Management**: Manajemen aset riil dan simulasi (Paper Trading).

## Arsitektur
Aplikasi ini menggunakan pola **MVVM (Model-View-ViewModel)** dengan Jetpack Compose sebagai UI framework.
- `agu.analys.engine`: Mesin evaluator teknis dan logika sinyal.
- `agu.analys.service`: Service untuk fetching data (Headlines, Indodax API).
- `agu.analys.viewmodel`: Koordinator logika bisnis dan state UI.
- `agu.analys.ui`: Komponen UI berbasis Material 3.

## Keamanan
- Kredensial API (Key & Secret) disimpan menggunakan `EncryptedSharedPreferences` yang terikat dengan Android Keystore.
- Akses fitur trading riil diproteksi dengan PIN 6-digit.

## Pengembangan & Testing
- **Unit Testing**: Fokus pada evaluator strategi di `app/src/test` menggunakan JUnit dan MockK.
- **Linting & Analysis**: Menggunakan `Detekt` untuk static code analysis.
- **Logging**: Menggunakan `Timber` untuk logging terpusat yang aman.
