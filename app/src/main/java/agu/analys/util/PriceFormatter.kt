package agu.analys.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

object PriceFormatter {

    /** Format harga dengan simbol mata uang dinamis (IDR / USDT / BIDR / USD) */
    fun formatPrice(price: Double, showSymbol: Boolean = true, quoteAsset: String = "IDR"): String {
        if (price.isNaN() || price.isInfinite()) {
            val isUsdt = quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)
            return if (!showSymbol) "0" else if (isUsdt) "$0.00" else "Rp 0"
        }
        if (price == 0.0) {
            val isUsdt = quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)
            return if (!showSymbol) "0" else if (isUsdt) "$0.00" else "Rp 0"
        }
        val isNegative = price < 0.0
        val absPrice = abs(price)
        val isUsdt = quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)
        val formatted = if (isUsdt) {
            val prefix = if (showSymbol) "$" else ""
            val symbols = DecimalFormatSymbols(Locale.US)
            when {
                absPrice < 0.00001 -> prefix + DecimalFormat("0.########", symbols).format(absPrice)
                absPrice < 0.001 -> prefix + DecimalFormat("0.######", symbols).format(absPrice)
                absPrice < 1.0 -> prefix + DecimalFormat("0.####", symbols).format(absPrice)
                absPrice < 10.0 -> prefix + DecimalFormat("0.###", symbols).format(absPrice)
                else -> prefix + DecimalFormat("#,##0.00", symbols).format(absPrice)
            }
        } else {
            val prefix = if (showSymbol) "Rp " else ""
            val symbols = DecimalFormatSymbols(Locale("id", "ID")).apply {
                groupingSeparator = '.'
                decimalSeparator = ','
            }
            when {
                absPrice < 0.00001 -> prefix + DecimalFormat("0.########", symbols).format(absPrice)
                absPrice < 0.01 -> prefix + DecimalFormat("0.######", symbols).format(absPrice)
                absPrice < 1.0 -> prefix + DecimalFormat("0.####", symbols).format(absPrice)
                absPrice < 100.0 && absPrice % 1.0 != 0.0 -> prefix + DecimalFormat("#,##0.##", symbols).format(absPrice)
                else -> {
                    val rounded = kotlin.math.round(absPrice).toLong()
                    prefix + DecimalFormat("#,##0", symbols).format(rounded)
                }
            }
        }
        return if (isNegative) "-$formatted" else formatted
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

    /** Format nominal IDR dengan dukungan pecahan desimal koin kecil (cth: 38.028 atau 0,00015 atau -40) */
    fun formatIdrNumber(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite()) return "0"
        if (amount == 0.0) return "0"
        val symbols = DecimalFormatSymbols(Locale("id", "ID")).apply {
            groupingSeparator = '.'
            decimalSeparator = ','
        }
        val absVal = abs(amount)
        val formatted = when {
            absVal < 0.00001 -> DecimalFormat("0.########", symbols).format(absVal)
            absVal < 0.01 -> DecimalFormat("0.######", symbols).format(absVal)
            absVal < 1.0 -> DecimalFormat("0.####", symbols).format(absVal)
            absVal < 100.0 && absVal % 1.0 != 0.0 -> DecimalFormat("#,##0.##", symbols).format(absVal)
            else -> {
                val rounded = kotlin.math.round(absVal).toLong()
                DecimalFormat("#,##0", symbols).format(rounded)
            }
        }
        return if (amount < 0) "- $formatted" else formatted
    }

    /** Parser serbaguna untuk input nominal IDR/koin dari pengguna (menangani titik/koma/spasi/teks) */
    fun parseCleanIdrDouble(input: String): Double {
        if (input.isBlank()) return 0.0
        val cleaned = input.trim()
            .replace("Rp", "", ignoreCase = true)
            .replace("IDR", "", ignoreCase = true)
            .replace("BTC", "", ignoreCase = true)
            .replace("USDT", "", ignoreCase = true)
            .replace("$", "")
            .trim()
        if (cleaned.isBlank()) return 0.0

        val hasComma = cleaned.contains(",")
        val hasDot = cleaned.contains(".")

        val sanitized = if (hasDot && hasComma) {
            val lastDot = cleaned.lastIndexOf('.')
            val lastComma = cleaned.lastIndexOf(',')
            if (lastDot > lastComma) {
                // Contoh: "1,367,959.50" -> koma ribuan, titik desimal
                cleaned.replace(",", "")
            } else {
                // Contoh: "1.367.959,50" -> titik ribuan, koma desimal
                cleaned.replace(".", "").replace(",", ".")
            }
        } else if (hasDot) {
            val dotCount = cleaned.count { it == '.' }
            if (dotCount > 1) {
                // Multiple dots (cth: "1.367.959.000" atau "2.500.000") -> pemisah ribuan
                cleaned.replace(".", "")
            } else {
                // Single dot: cth "41.00", "41.0", "41.25", "0.5", "0.00015", "1.000"
                val parts = cleaned.split('.')
                val beforeDot = parts.getOrNull(0).orEmpty().trim()
                val afterDot = parts.getOrNull(1).orEmpty().trim()
                if (beforeDot == "0" || afterDot.length != 3 || beforeDot.length > 3) {
                    cleaned
                } else {
                    // Integer ribuan seperti 1.000, 50.000
                    cleaned.replace(".", "")
                }
            }
        } else if (hasComma) {
            val commaCount = cleaned.count { it == ',' }
            if (commaCount > 1) {
                cleaned.replace(",", "")
            } else {
                // Single comma: cth "41,50" atau "0,00015" -> koma adalah desimal
                cleaned.replace(",", ".")
            }
        } else {
            cleaned
        }

        return sanitized.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() ?: 0.0
    }

    /** Helper umum untuk format angka desimal ringkas di evaluator & sinyal */
    fun fmt(v: Double, decimals: Int = 2): String =
        String.format(Locale.US, "%.${decimals}f", v)

    /** Helper umum untuk format angka harga ringkas di evaluator & sinyal */
    fun fmtPrice(v: Double): String =
        when {
            v >= 1000.0 -> String.format(Locale.US, "%,.0f", v)
            v >= 1.0 -> String.format(Locale.US, "%.2f", v)
            v >= 0.01 -> String.format(Locale.US, "%.4f", v)
            v > 0.0 -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
            else -> "0"
        }

    /** Helper umum untuk format angka harga bulat integer di evaluator & sinyal */
    fun fmtPriceInt(v: Double): String =
        String.format(Locale.US, "%,.0f", v)

    fun formatCoinQuantity(quantity: java.math.BigDecimal, baseAsset: String, decimals: Int): String {
        if (quantity.compareTo(java.math.BigDecimal.ZERO) == 0) return "0 $baseAsset"
        val formatted = quantity.setScale(decimals, java.math.RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
        return "$formatted $baseAsset"
    }

    fun formatCoinQuantity(quantity: Double, baseAsset: String, decimals: Int): String {
        return formatCoinQuantity(java.math.BigDecimal.valueOf(quantity), baseAsset, decimals)
    }
}
