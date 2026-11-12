package agu.analys.util

import android.content.Context

/**
 * Stub smoke untuk `AppPreferences` produksi.
 *
 * `AgentHttpServer.installRoutes()` membaca `agentBridgeToken` dari sini; token tetap
 * dijaga non-kosong supaya uji 401 (tanpa header) benar-benar menguji fail-closed,
 * bukan "server kehilangan token".
 *
 * Stub ini **wajib** sejalan dengan `agu/analys/util/AppPreferences.kt` asli: kalau nama
 * properti berubah, smoke harus gagal编译 — bukan lolos diam-diam.
 */
class AppPreferences(@Suppress("UNUSED_PARAMETER") context: Context) {
    val agentBridgeToken: String = "smoke-bridge-token"
}
