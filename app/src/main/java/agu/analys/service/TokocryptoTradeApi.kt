package agu.analys.service

import agu.analys.network.NetworkClientProvider
import agu.analys.service.IndodaxTradeApiV2.IndodaxBalances
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * API Tokocrypto untuk manajemen akun dan saldo riil spot (SSOT) dengan fallback Binance Cloud.
 */
object TokocryptoTradeApi {
    private const val TOKOCRYPTO_BASE_URL = "https://www.tokocrypto.com"
    private const val BINANCE_BASE_URL = "https://api.binance.com"
    private val client get() = NetworkClientProvider.tradeClient

    private fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(value: String): String =
        try {
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }

    /**
     * Mengambil saldo riil spot Tokocrypto dengan fallback Binance Cloud API.
     */
    suspend fun getAccount(apiKey: String, secretKey: String): Pair<IndodaxBalances?, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return@withContext null to "API Key atau Secret Key Tokocrypto belum diisi."
        }

        val timestamp = System.currentTimeMillis().toString()
        val recvWindow = "10000"
        val queryParam = "recvWindow=$recvWindow&timestamp=$timestamp"
        val signature = hmacSha256(secretKey, queryParam)

        // 1. Coba endpoint Tokocrypto Open API
        try {
            val url = "$TOKOCRYPTO_BASE_URL/open/v1/account/spot/asset?$queryParam&signature=$signature"
            val req = Request.Builder()
                .url(url)
                .get()
                .header("X-MBX-APIKEY", apiKey)
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val root = JSONObject(body)
                    val code = root.optInt("code", -1)
                    val data = root.optJSONArray("data")
                    if (code == 0 && data != null) {
                        val parsed = parseBalances(data)
                        return@withContext parsed to "Saldo Tokocrypto berhasil diperbarui."
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Tokocrypto API getAccount gagal, beralih ke Binance Cloud...")
        }

        // 2. Fallback: Binance Cloud Account API
        try {
            val url = "$BINANCE_BASE_URL/api/v3/account?$queryParam&signature=$signature"
            val req = Request.Builder()
                .url(url)
                .get()
                .header("X-MBX-APIKEY", apiKey)
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val root = JSONObject(body)
                    val balances = root.optJSONArray("balances")
                    if (balances != null) {
                        val parsed = parseBalances(balances)
                        return@withContext parsed to "Saldo Tokocrypto/Binance berhasil diperbarui."
                    }
                } else if (resp.code == 401 || resp.code == 400) {
                    val root = runCatching { JSONObject(body) }.getOrNull()
                    val msg = root?.optString("msg", "Kredensial API tidak valid.") ?: "Kredensial API tidak valid."
                    return@withContext null to "Error Tokocrypto/Binance: $msg"
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Binance Cloud getAccount gagal: ${e.message}")
            return@withContext null to "Gagal menghubungkan ke server Tokocrypto/Binance: ${e.message}"
        }

        null to "Gagal memproses saldo akun Tokocrypto."
    }

    private fun parseBalances(array: JSONArray): IndodaxBalances {
        val total = mutableMapOf<String, Double>()
        val free = mutableMapOf<String, Double>()
        val locked = mutableMapOf<String, Double>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val asset = item.optString("asset", "").lowercase()
            if (asset.isBlank()) continue

            val f = item.optString("free", "0").toDoubleOrNull() ?: 0.0
            val l = item.optString("locked", "0").toDoubleOrNull() ?: 0.0
            val t = f + l

            if (t > 0.0) {
                total[asset] = t
                free[asset] = f
                locked[asset] = l

                // Ekuivalen BIDR <-> IDR untuk konsistensi UI
                if (asset == "bidr") {
                    total["idr"] = t
                    free["idr"] = f
                    locked["idr"] = l
                }
            }
        }

        return IndodaxBalances(total = total, free = free, locked = locked)
    }
}
