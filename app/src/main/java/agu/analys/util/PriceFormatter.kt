package agu.analys.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

object PriceFormatter {

    /** Format harga Rupiah lengkap: Rp 1.542.728.000 */
    fun formatPrice(price: Double, showSymbol: Boolean = true): String {
        if (price.isNaN() || price.isInfinite() || price <= 0) {
            return if (showSymbol) "Rp 0" else "0"
        }
        val prefix = if (showSymbol) "Rp " else ""
        val rounded = kotlin.math.round(price).toLong()
        val symbols = DecimalFormatSymbols(Locale("id", "ID")).apply {
            groupingSeparator = '.'
            decimalSeparator = ','
        }
        // Harga kecil (meme) boleh desimal
        return if (price < 1.0) {
            prefix + DecimalFormat("0.########", symbols).format(price)
        } else {
            prefix + DecimalFormat("#,##0", symbols).format(rounded)
        }
    }

    /** Alias — selalu full IDR (bukan compact) untuk level AI */
    fun formatPriceFull(price: Double): String = formatPrice(price, showSymbol = true)

    fun formatVolume(volume: Double): String {
        if (volume.isNaN() || volume.isInfinite() || volume == 0.0) return "Rp 0"
        val absVol = abs(volume)
        val symbols = DecimalFormatSymbols(Locale("id", "ID")).apply {
            groupingSeparator = '.'
            decimalSeparator = ','
        }
        return when {
            absVol >= 1_000_000_000_000.0 ->
                "Rp " + DecimalFormat("#.##", symbols).format(volume / 1_000_000_000_000.0) + " T"
            absVol >= 1_000_000_000.0 ->
                "Rp " + DecimalFormat("#.##", symbols).format(volume / 1_000_000_000.0) + " M"
            absVol >= 1_000_000.0 ->
                "Rp " + DecimalFormat("#.##", symbols).format(volume / 1_000_000.0) + " jt"
            absVol >= 1_000.0 ->
                "Rp " + DecimalFormat("#.##", symbols).format(volume / 1_000.0) + " rb"
            else -> formatPrice(volume)
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
}
