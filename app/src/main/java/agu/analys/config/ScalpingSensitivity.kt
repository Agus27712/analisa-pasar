package agu.analys.config

/**
 * Sensitivitas evaluasi engine Scalping.
 * - CONSERVATIVE: Filter ketat MTF (1H bias wajib di atas EMA20, RSI 38-62, Net R:R >= 1.2)
 * - AGGRESSIVE: Filter lebih longgar untuk frekuensi sinyal lebih sering (RSI 35-66, Net R:R >= 1.1)
 */
enum class ScalpingSensitivity(val label: String, val description: String) {
    CONSERVATIVE(
        label = "Konservatif",
        description = "SL 0.30–0.55% · Vol 5 candle · Bias EMA H1 · Trigger Boolean · Net R:R min 1.25"
    ),
    BALANCED(
        label = "Seimbang (Rekomendasi)",
        description = "SL 0.40–0.80% · Ambang RSI 36–64 · Vol 1.0x · Adaptif Walk-Forward · Net R:R min 1.20"
    ),
    AGGRESSIVE(
        label = "Agresif",
        description = "SL 0.60–1.20% (ATR x1.5) · Vol 15 candle · Bias EMA+RSI H1>50 · Trigger Multi-Level Scoring · Net R:R min 1.15"
    ),
    DYNAMIC_AUTO(
        label = "Adaptif Otomatis (AI)",
        description = "Otomatis menyesuaikan threshold RSI, EMA, & R:R secara dinamis sesuai Rejim Pasar (Sideways/Volatile/Trending)"
    )
}
