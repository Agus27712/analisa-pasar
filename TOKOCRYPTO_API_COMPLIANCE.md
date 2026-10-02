# Tokocrypto API Compliance Check

## Kesimpulan singkat

Kode yang Anda kirim di `app/src/main/java/agu/analys/service/TokocryptoTradeApi.kt` sudah sangat dekat dengan pola official Tokocrypto signed trading API. Namun ada 1 hal yang paling kritikal dan sering menyebabkan order ditolak: "signature harus dibuat dari param yang sudah diurutkan secara alphabetic". Di `createOrder()` saat ini Anda masih memakai `formParams.joinToString("&")` tanpa sort. Ini bisa menyebabkan signature mismatch dan order ditolak meskipun semua param lain benar.

---

## Endpoint resmi Tokocrypto yang dipakai

Base URL:
```
https://www.tokocrypto.com
```

Endpoint trading resmi yang relevan:
- `GET /open/v1/account/spot`
- `POST /open/v1/orders`
- `GET /open/v1/orders/detail`
- `POST /open/v1/orders/cancel`
- `POST /open/v1/user-listen-token`

Catatan penting:
- `GET /open/v1/account/spot/asset` biasanya membutuhkan parameter `asset` dan tidak cocok untuk "ambil semua saldo".
- Karena itu, versi yang Anda kirim (`ACCOUNT_ENDPOINTS = listOf("/open/v1/account/spot")`) itu benar untuk kebutuhan `getAccount()`.

---

## Yang sudah sesuai dengan official pattern

### 1) Base URL dan header
```kotlin
.header("X-MBX-APIKEY", apiKey.trim())
```
Ini sesuai pola signed API berbasis Binance-style.

### 2) Signature HMAC-SHA256
```kotlin
private fun hmacSha256(secret: String, payload: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
```
Ini benar.

### 3) Symbol format
```kotlin
val tokoSymbol = TokocryptoSymbolRepository.getSymbolInfo(request.symbol)
    ?.let { "${it.baseAsset}_${it.quoteAsset}" }
    ?: TokocryptoMarketService.toTokocryptoPair(request.symbol)
```
Benar: Tokocrypto memakai format `BTC_USDT`, bukan `BTCUSDT`.

### 4) `side` dan `type` code
```kotlin
formParams.add("side" to request.side.code.toString())
formParams.add("type" to request.type.code.toString())
```
Benar untuk API numeric code style.

### 5) `clientId` tidak dikirim
Ini sangat penting karena komentar Anda benar: custom `clientId` format teks bisa ditolak dengan kode `3703`.

### 6) `timeInForce` diubah ke kode angka
```kotlin
when (request.timeInForce.trim().uppercase()) {
    "GTC" -> "1"
    "IOC" -> "2"
    "FOK" -> "3"
    "GTX" -> "4"
}
```
Ini sudah mengikuti pola signed API numeric TIF. Memang beberapa exchange memakai angka, bukan teks.

---

## Risiko paling besar yang masih ada

### Critical: parameter sorting sebelum signature
Ini yang paling sering menyebabkan order ditolak.

Saat ini di `createOrder()` Anda punya:
```kotlin
val queryString = formParams.joinToString("&") { "${it.first}=${it.second}" }
val signature = hmacSha256(secretKey, queryString)
```

Pada API Binance/Tokocrypto-style, SIGNATURE HARUS DIBUAT DARI QUERY STRING YANG SUDAH DIURUTKAN SECARA ALPHABETIC: `key1=value1&key2=value2...`

Jika urutannya tidak konsisten, signature akan invalid walaupun request lainnya benar. Hasilnya: order ditolak dengan error seperti:
- `Signature for this request is not valid`
- `code: -1022`

### Fix yang aman
```kotlin
val queryString = formParams
    .sortedBy { it.first }
    .joinToString("&") { "${it.first}=${it.second}" }
val signature = hmacSha256(secretKey, queryString)
```

Gunakan pola yang sama untuk semua request signed.

---

## Fix yang disarankan

### A) createOrder(): sort param sebelum signature
```kotlin
val formParams = mutableListOf<Pair<String, String>>()
formParams.add("symbol" to tokoSymbol)
formParams.add("side" to request.side.code.toString())
formParams.add("type" to request.type.code.toString())

if (request.quantity != null && request.quantity > 0) {
    formParams.add("quantity" to formatParam(valResult.adjustedQty, 8, RoundingMode.DOWN))
}
if (request.quoteOrderQty != null && request.quoteOrderQty > 0) {
    formParams.add("quoteOrderQty" to formatParam(request.quoteOrderQty, 2, RoundingMode.DOWN))
}
if (request.price != null && request.price > 0 && !isMarket) {
    formParams.add("price" to formatParam(valResult.adjustedPrice, 8, RoundingMode.HALF_UP))
}
if (request.stopPrice != null && request.stopPrice > 0) {
    formParams.add("stopPrice" to formatParam(request.stopPrice, 8, RoundingMode.HALF_UP))
}
if (!request.timeInForce.isNullOrBlank() && !isMarket) {
    val tifCode = when (request.timeInForce.trim().uppercase()) {
        "GTC" -> "1"
        "IOC" -> "2"
        "FOK" -> "3"
        "GTX" -> "4"
        else -> request.timeInForce.trim()
    }
    formParams.add("timeInForce" to tifCode)
}
formParams.add("recvWindow" to request.recvWindow.toString())
formParams.add("timestamp" to timestamp)

val queryString = formParams
    .sortedBy { it.first }
    .joinToString("&") { "${it.first}=${it.second}" }

val signature = hmacSha256(secretKey, queryString)
```

### B) getAccount(): juga pastikan query ordering tetap benar
```kotlin
val params = listOf(
    "recvWindow=$recvWindow",
    "timestamp=$timestamp"
)
val queryParam = params.sorted().joinToString("&")
val signature = hmacSha256(secretKey, queryParam)
```

### C) Pastikan semua signed requests memakai pola yang sama
- `timestamp` harus milis saat ini
- `recvWindow` harus ada
- `signature` dihitung dari query string
- `symbol` harus `BASE_QUOTE`
- jangan kirim custom `clientId`

---

## Kesimpulan: apakah kode ini akan menolak order?

### Jawaban:
- Dengan kode yang Anda kirim di file prompt, `risk order ditolak` masih ada, tapi bukan karena API Tokocrypto-nya salah.
- Risiko utama adalah `signature generation` dan `query parameter ordering`.
- Yang sudah benar di file Anda: endpoint, header, HMAC, symbol format, side/type, TIF conversion, dan no custom clientId.
- Yang perlu diperbaiki agar aman: `queryString` harus di-sort sebelum signature dibentuk.

Jadi, jika Anda perbaiki point ini, kode tersebut kemungkinan besar akan bersesuaian dengan API resmi Tokocrypto dan tidak akan reject karena format dasar.

---

## Recomendasi final

Lakukan patch ini di `createOrder()` dan semua signed request lainnya. Setelah itu, lakukan test nyata dengan API key Tokocrypto:
1. `getAccount()`
2. `createOrder()` LIMIT
3. `createOrder()` MARKET
4. `cancelOrder()`
5. `getOrder()`

Jika test live berhasil, berarti integrasi Anda sudah sesuai dengan API resmi Tokocrypto.
