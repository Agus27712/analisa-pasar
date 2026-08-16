package agu.analys.config

/**
 * Sensitivitas evaluasi engine Scalping.
 * - CONSERVATIVE: Filter ketat MTF (1H bias wajib di atas EMA20, RSI 38-62, Net R:R >= 1.2)
 * - AGGRESSIVE: Filter lebih longgar untuk frekuensi sinyal lebih sering (RSI 35-66, Net R:R >= 1.1)
 */
enum class ScalpingSensitivity(val label: String, val description: String) {
    CONSERVATIVE(
        label = "Konservatif",
        description = "Sangat selektif · Anti-false breakout · Net R:R min 1.2 · Aman dari churn fee"
    ),
    AGGRESSIVE(
        label = "Agresif",
        description = "Peluang lebih sering · RSI 35–66 · Menangkap quick pump lebih awal"
    )
}
