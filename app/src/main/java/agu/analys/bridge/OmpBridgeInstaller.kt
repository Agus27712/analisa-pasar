package agu.analys.bridge

import android.content.Context
import android.content.Intent

/**
 * Pemasangan sekali klik ekstensi `oh-my-pi` (instrumen bridge) ke Termux dari Pengaturan.
 *
 * Kenapa bukan "salin ke Documents"? Pada targetSdk 35 aplikasi ini **tidak bisa** menulis ke
 * folder publik `Documents` (scoped storage), sehingga hasil salinannya tidak akan terbaca
 * Termux. Satu-satunya cara yang tidak bergantung izin penyimpanan adalah mengirim **perintah**
 * yang menulis `index.ts` langsung ke `~/.config/oh-my-pi/extensions/analisa-pasar/`:
 *
 * 1. Jalur otomatis — intent `com.termux.RUN_COMMAND`. Termux menjalankannya di shell-nya sendiri.
 *    Syarat: `allow-external-apps=true` di `~/.termux/termux.properties`. Bila prasyarat itu
 *    belum diset, Termux tidak mengeksekusi apa pun (tidak ada kesalahan di aplikasi).
 * 2. Jalur manual — perintah yang sama, disalin ke clipboard; pengguna menempelnya di Termux.
 *    Tidak perlu setelan apa pun, hanya butuh Termux terpasang.
 *
 * Isi `index.ts` dikirim sebagai base64 agar perintah tetap satu baris dan tidak bermasalah
 * dengan karakter khusus saat dimasukkan ke Termux.
 */
object OmpBridgeInstaller {

    /** Direktori aset hasil `syncOmpExtensionAssets` (di-generate saat build dari `tools/oh-my-pi-extension`). */
    const val ASSET_DIR = "omp-extension"

    /** Tujuan di dalam Termux — tidak pernah disentuh aplikasi. */
    const val DESTINATION_DIR = "~/.config/oh-my-pi/extensions/analisa-pasar"

    data class Prepared(val fileName: String, val base64: String, val command: String)

    /** Membaca aset dan merakit perintah pemasangan. Melempar bila aset tidak ada. */
    fun prepare(context: Context, fileName: String = "index.ts"): Prepared {
        val bytes = context.assets.open("$ASSET_DIR/$fileName").use { it.readBytes() }
        val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val command = buildInstallCommand(base64, fileName)
        return Prepared(fileName, base64, command)
    }

    fun buildInstallCommand(base64: String, fileName: String = "index.ts"): String =
        "mkdir -p $DESTINATION_DIR && printf '%s' '$base64' | base64 -d > $DESTINATION_DIR/$fileName"

    /**
     * Mengirim perintah pemasangan ke Termux lewat intent `com.termux.RUN_COMMAND`.
     * Tidak bisa dipastikan suksesnya — yang pasti hanya "Termux berhasil dibuka".
     * @return `true` bila Termux menerima intent (Termux terpasang), `false` bila tidak.
     */
    fun runInTermux(context: Context, command: String): Boolean {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setPackage("com.termux")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }
}
