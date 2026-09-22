package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import agu.analys.config.AiProvider
import agu.analys.model.AISignalState
import agu.analys.model.MarketTick
import agu.analys.model.NewsScreenerUiState
import agu.analys.model.TechnicalIndicators
import agu.analys.model.TradingPair
import agu.analys.service.GeminiAiService
import agu.analys.service.GroqAiService
import agu.analys.service.NewsAiScreenerService
import agu.analys.service.NewsRssFeedService
import agu.analys.util.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AiNewsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    private val _newsScreenerState = MutableStateFlow<NewsScreenerUiState>(NewsScreenerUiState.Idle)
    val newsScreenerState: StateFlow<NewsScreenerUiState> = _newsScreenerState.asStateFlow()

    private val _auditReportText = MutableStateFlow<String?>(null)
    val auditReportText: StateFlow<String?> = _auditReportText.asStateFlow()

    private val _isAuditLoading = MutableStateFlow(false)
    val isAuditLoading: StateFlow<Boolean> = _isAuditLoading.asStateFlow()

    private val _geminiSummaryText = MutableStateFlow<String?>(null)
    val geminiSummaryText: StateFlow<String?> = _geminiSummaryText.asStateFlow()

    private val _isGeminiLoading = MutableStateFlow(false)
    val isGeminiLoading: StateFlow<Boolean> = _isGeminiLoading.asStateFlow()

    fun fetchAiNewsScreening(groqKey: String = "", geminiKey: String = "", forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _newsScreenerState.value = NewsScreenerUiState.Loading("Mengambil & menganalisa feed berita kripto terbaru...")
            try {
                val effectiveGroq = groqKey.ifBlank { prefs.groqApiKey }
                val effectiveGemini = geminiKey.ifBlank { prefs.geminiApiKey }
                val provider = prefs.aiProvider

                val articles = NewsRssFeedService.fetchAggregatedNews(forceRefresh = forceRefresh)
                if (articles.isEmpty()) {
                    _newsScreenerState.value = NewsScreenerUiState.Error("Tidak ada headline berita yang berhasil dimuat dari feed RSS.")
                    return@launch
                }

                // Gunakan daftar koin populer Indodax sebagai basis penyaringan
                val dynamicBases = TradingPair.POPULAR_INDODAX_PAIRS.map { it.baseAsset.uppercase() }.toMutableSet()
                val liveTicks = emptyMap<String, agu.analys.model.MarketTick>()

                val result = NewsAiScreenerService.screenCoinsFromNews(
                    articles = articles,
                    indodaxValidBases = dynamicBases,
                    liveTicks = liveTicks,
                    preferredProvider = provider,
                    groqApiKey = effectiveGroq,
                    geminiApiKey = effectiveGemini
                )
                _newsScreenerState.value = NewsScreenerUiState.Success(result)
            } catch (e: Exception) {
                _newsScreenerState.value = NewsScreenerUiState.Error("Gagal screening berita: ${e.message}")
            }
        }
    }

    fun clearNewsScreenerState() {
        _newsScreenerState.value = NewsScreenerUiState.Idle
    }

    fun requestDeepAiAudit(
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState,
        groqApiKey: String? = null
    ) {
        if (_isAuditLoading.value || _isGeminiLoading.value) return
        viewModelScope.launch {
            _isAuditLoading.value = true
            _auditReportText.value = null
            try {
                val effectiveKey = groqApiKey?.ifBlank { null } ?: prefs.groqApiKey
                _auditReportText.value = GroqAiService.generateDeepMarketAudit(
                    effectiveKey, tick, indicators, signal
                )
            } catch (e: Exception) {
                _auditReportText.value = "Gagal audit AI: ${e.message}"
            } finally {
                _isAuditLoading.value = false
            }
        }
    }

    fun clearAuditReport() {
        _auditReportText.value = null
    }

    fun requestGeminiChartSummary(
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState,
        geminiApiKey: String? = null
    ) {
        if (_isAuditLoading.value || _isGeminiLoading.value) return
        viewModelScope.launch {
            _isGeminiLoading.value = true
            _geminiSummaryText.value = null
            try {
                val effectiveKey = geminiApiKey?.ifBlank { null } ?: prefs.geminiApiKey
                _geminiSummaryText.value = GeminiAiService.generateChartSummary24h(
                    effectiveKey, tick, indicators, signal
                )
            } catch (e: Exception) {
                _geminiSummaryText.value = "Gagal analisa AI: ${e.message}"
            } finally {
                _isGeminiLoading.value = false
            }
        }
    }

    fun clearGeminiSummary() {
        _geminiSummaryText.value = null
    }

    fun summarizeCurrentMarketWithAi(symbol: String, price: Double, rsi: Double, ema20: Double, reasoning: List<String>) {
        viewModelScope.launch {
            _isGeminiLoading.value = true
            _geminiSummaryText.value = null
            try {
                val groqKey = prefs.groqApiKey
                val geminiKey = prefs.geminiApiKey
                val provider = prefs.aiProvider

                val prompt = """
                    Analisa koin: $symbol
                    Harga saat ini: Rp ${String.format(java.util.Locale.US, "%,.0f", price)}
                    RSI M1: ${String.format(java.util.Locale.US, "%.2f", rsi)}
                    EMA20 M1: ${String.format(java.util.Locale.US, "%,.0f", ema20)}
                    Pemicu Masuk/Tahan: ${reasoning.joinToString("; ")}
                    
                    Tolong berikan rekomendasi ringkas (maksimal 3 kalimat) dengan gaya seorang trader ahli profesional: apakah aman beli sekarang, atau tunggu pullback. Tulis dalam bahasa Indonesia yang lugas dan tajam.
                """.trimIndent()

                val summary = if (provider == AiProvider.GEMINI) {
                    GeminiAiService.generateSimpleText(geminiKey, prompt)
                } else {
                    GroqAiService.generateText(groqKey, prompt)
                }
                _geminiSummaryText.value = summary
            } catch (e: Exception) {
                _geminiSummaryText.value = "Gagal analisa AI: ${e.message}"
            } finally {
                _isGeminiLoading.value = false
            }
        }
    }
}
