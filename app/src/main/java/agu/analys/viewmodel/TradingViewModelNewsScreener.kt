package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.config.AiProvider
import agu.analys.model.NewsScreenerUiState
import agu.analys.model.TradingPair
import agu.analys.service.NewsAiScreenerService
import agu.analys.service.NewsRssFeedService
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Extension ViewModel untuk mengelola alur kerja AI Screener Berita (Dashboard Shortcut)
 */
fun TradingViewModel.runNewsAiScreener(
    forceRefresh: Boolean = false,
    overrideProvider: AiProvider? = null
) {
    if (_newsScreenerState.value is NewsScreenerUiState.Loading) return

    viewModelScope.launch {
        try {
            _newsScreenerState.value = NewsScreenerUiState.Loading("Mengumpulkan berita RSS crypto global & ID...")

            val articles = NewsRssFeedService.fetchAggregatedNews(forceRefresh = forceRefresh)
            if (articles.isEmpty()) {
                _newsScreenerState.value = NewsScreenerUiState.Error("Tidak ada headline berita yang berhasil dimuat dari feed RSS.")
                return@launch
            }

            _newsScreenerState.value = NewsScreenerUiState.Loading("Menganalisis katalis & memfilter koin listing Indodax...")

            // Ambil seluruh koin yang aktif diperdagangkan di Indodax secara dinamis
            val allTicks = dashboardTicks.value +
                hotCoins.value.associateBy { it.symbol } +
                gainersCoins.value.associateBy { it.symbol } +
                secondWaveCoins.value.associateBy { it.symbol } +
                topVolumeCoins.value.associateBy { it.symbol }

            val dynamicBases = allTicks.keys.map { key ->
                key.uppercase()
                    .replace("_IDR", "")
                    .replace("_USDT", "")
                    .replace("IDR", "")
                    .replace("USDT", "")
            }.filter { it.isNotBlank() }.toMutableSet()

            // Tambahkan daftar koin populer Indodax sebagai referensi dasar
            TradingPair.POPULAR_INDODAX_PAIRS.forEach { pair ->
                dynamicBases.add(pair.baseAsset.uppercase())
            }

            val targetProvider = overrideProvider ?: prefs.aiProvider

            val result = NewsAiScreenerService.screenCoinsFromNews(
                articles = articles,
                indodaxValidBases = dynamicBases,
                liveTicks = allTicks,
                preferredProvider = targetProvider,
                groqApiKey = prefs.groqApiKey,
                geminiApiKey = prefs.geminiApiKey
            )

            _newsScreenerState.value = NewsScreenerUiState.Success(result)
        } catch (e: Exception) {
            Timber.e(e, "Gagal menjalankan News AI Screener")
            _newsScreenerState.value = NewsScreenerUiState.Error(
                e.localizedMessage ?: "Terjadi kendala saat memproses analisis berita AI."
            )
        }
    }
}

fun TradingViewModel.clearNewsScreenerState() {
    _newsScreenerState.value = NewsScreenerUiState.Idle
}
