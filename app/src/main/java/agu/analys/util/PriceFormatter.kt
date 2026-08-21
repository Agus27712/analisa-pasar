package agu.analys.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

object PriceFormatter {

    /** Format harga dengan simbol mata uang dinamis (IDR / USDT / BIDR / USD) */
    fun formatPrice(price: Double, showSymbol: Boolean = true, quoteAsset: String = "IDR"): String {
        if (price.isNaN() || price.isInfinite() || price <= 0) {
            val isUsdt = quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)
            return if (!showSymbol) "0" else if (isUsdt) "$0.00" else "Rp 0"
        }
        val isUsdt = quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)
        if (isUsdt) {
            val prefix = if (showSymbol) "$" else ""
            val symbols = DecimalFormatSymbols(Locale.US)
            return when {
                price < 0.0001 -> prefix + DecimalFormat("0.########", symbols).format(price)
                price < 1.0 -> prefix + DecimalFormat("0.######", symbols).format(price)
                price < 10.0 -> prefix + DecimalFormat("0.####", symbols).format(price)
                else -> prefix + DecimalFormat("#,##0.00", symbols).format(price)
            }
        } else {
            val prefix = if (showSymbol) "Rp " else ""
            val rounded = kotlin.math.round(price).toLong()
            val symbols = DecimalFormatSymbols(Locale("id", "ID")).apply {
                groupingSeparator = '.'
                decimalSeparator = ','
            }
            return if (price < 1.0) {
                prefix + DecimalFormat("0.########", symbols).format(price)
            } else {
                prefix + DecimalFormat("#,##0", symbols).format(rounded)
            }
        }
    }

    /** Alias — selalu full price untuk level AI */
    fun formatPriceFull(price: Double, quoteAsset: String = "IDR"): String =
        formatPrice(price, showSymbol = true, quoteAsset = quoteAsset)

    fun formatVolume(volume: Double, quoteAsset: String = "IDR"): String {
        if (volume.isNaN() || volume.isInfinite() || volume == 0.0) {
            return if (quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)) "$0" else "Rp 0"
        }
        val absVol = abs(volume)
        val isUsdt = quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)
        if (isUsdt) {
            val symbols = DecimalFormatSymbols(Locale.US)
            return when {
                absVol >= 1_000_000_000.0 ->
                    "$" + DecimalFormat("#.##", symbols).format(volume / 1_000_000_000.0) + " B"
                absVol >= 1_000_000.0 ->
                    "$" + DecimalFormat("#.##", symbols).format(volume / 1_000_000.0) + " M"
                absVol >= 1_000.0 ->
                    "$" + DecimalFormat("#.##", symbols).format(volume / 1_000.0) + " K"
                else -> "$" + DecimalFormat("#.##", symbols).format(volume)
            }
        } else {
            val symbols = DecimalFormatSymbols(Locale("id", "ID")).apply {
                groupingSeparator = '.'
                decimalSeparator = ','
            }
            return when {
                absVol >= 1_000_000_000_000.0 ->
                    "Rp " + DecimalFormat("#.##", symbols).format(volume / 1_000_000_000_000.0) + " T"
                absVol >= 1_000_000_000.0 ->
                    "Rp " + DecimalFormat("#.##", symbols).format(volume / 1_000_000_000.0) + " Mil"
                absVol >= 1_000_000.0 ->
                    "Rp " + DecimalFormat("#.##", symbols).format(volume / 1_000_000.0) + " jt"
                absVol >= 1_000.0 ->
                    "Rp " + DecimalFormat("#.##", symbols).format(volume / 1_000.0) + " rb"
                else -> formatPrice(volume, showSymbol = true, quoteAsset = quoteAsset)
            }
        }
    }

    fun formatPercentage(change: Double, includePlusSign: Boolean = true): String {
        if (change.isNaN() || change.isInfinite()) return "0.00%"
        val symbols = DecimalFormatSymbols(Locale.US)
        val formatted = DecimalFormat("0.00", symbols).format(abs(change))
        return when {
            change > 0 -> if (includePlusSign) "+$formatted%" else "$formatted%"
            change < 0 -> "-$formatted%"
            else -> "0.00%"
        }
    }

    fun formatRsi(rsi: Double): String {
        if (rsi.isNaN() || rsi.isInfinite()) return "50.0"
        return DecimalFormat("0.0", DecimalFormatSymbols(Locale.US)).format(rsi)
    }

    fun formatIndicatorVal(value: Double, decimals: Int = 2): String {
        if (value.isNaN() || value.isInfinite()) return "0.0"
        val pattern = buildString {
            append("0.")
            repeat(decimals) { append("0") }
        }
        return DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(value)
    }

    fun formatRawDecimal(value: Double): String {
        if (value.isNaN() || value.isInfinite() || value <= 0.0) return "0"
        return if (value >= 1.0) {
            if (value % 1.0 == 0.0) {
                value.toLong().toString()
            } else {
                String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
            }
        } else {
            String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
        }
    }

    fun formatQuantity(quantity: Double): String {
        if (quantity.isNaN() || quantity.isInfinite() || quantity <= 0.0) return "0"
        return if (quantity >= 1000.0) {
            String.format(Locale.US, "%,.2f", quantity).replace(",", ".")
        } else if (quantity >= 1.0) {
            String.format(Locale.US, "%.4f", quantity).trimEnd('0').trimEnd('.')
        } else {
            String.format(Locale.US, "%.6f", quantity).trimEnd('0').trimEnd('.')
        }
    }

    /** Format desimal koin kripto presisi tinggi (cth: 0,00002774 BTC) */
    fun formatCryptoExact(amount: Double, maxDecimals: Int = 8): String {
        if (amount.isNaN() || amount.isInfinite() || amount <= 0.0) return "0"
        val symbols = DecimalFormatSymbols(Locale("id", "ID")).apply {
            groupingSeparator = '.'
            decimalSeparator = ','
        }
        val pattern = "0." + "#".repeat(maxDecimals.coerceIn(2, 10))
        return DecimalFormat(pattern, symbols).format(amount)
    }

    /** Format nominal integer IDR tanpa desimal (cth: 38.028 IDR atau - 40 IDR) */
    fun formatIdrNumber(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite()) return "0"
        val symbols = DecimalFormatSymbols(Locale("id", "ID")).apply {
            groupingSeparator = '.'
            decimalSeparator = ','
        }
        val rounded = kotlin.math.round(abs(amount)).toLong()
        val formatted = DecimalFormat("#,##0", symbols).format(rounded)
        return if (amount < 0) "- $formatted" else formatted
    }

    /** Parser serbaguna untuk input nominal IDR/koin dari pengguna (menangani titik/koma/spasi/teks) */
    fun parseCleanIdrDouble(input: String): Double {
        if (input.isBlank()) return 0.0
        val cleaned = input.trim()
            .replace("Rp", "", ignoreCase = true)
            .replace("IDR", "", ignoreCase = true)
            .replace("BTC", "", ignoreCase = true)
            .trim()
        if (cleaned.isBlank()) return 0.0

        val hasComma = cleaned.contains(",")
        val hasDot = cleaned.contains(".")

        val sanitized = if (hasDot && hasComma) {
            // Contoh: "1.367.959,50" -> titik adalah ribuan, koma adalah desimal
            cleaned.replace(".", "").replace(",", ".")
        } else if (hasDot) {
            // Dalam konteks IDR Indodax, titik digunakan sebagai pemisah ribuan (contoh: "37.987" atau "1.367.959.000")
            cleaned.replace(".", "")
        } else if (hasComma) {
            cleaned.replace(",", ".")
        } else {
            cleaned
        }

        return sanitized.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
    }
}
