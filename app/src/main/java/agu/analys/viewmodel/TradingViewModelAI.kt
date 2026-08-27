package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.model.MarketConnectionState
import agu.analys.service.GeminiAiService
import agu.analys.service.GroqAiService
import kotlinx.coroutines.launch

fun TradingViewModel.requestDeepAiAudit() {
    val tick = _currentTick.value ?: return
    if (_connectionState.value !is MarketConnectionState.Connected || _isAuditLoading.value || _isGeminiLoading.value) return
    viewModelScope.launch {
        _isAuditLoading.value = true
        _auditReportText.value = null
        try {
            _auditReportText.value = GroqAiService.generateDeepMarketAudit(
                prefs.groqApiKey, tick, currentIndicators.value, aiSignalState.value
            )
        } finally { _isAuditLoading.value = false }
    }
}

fun TradingViewModel.clearAuditReport() { _auditReportText.value = null }

fun TradingViewModel.requestGeminiChartSummary() {
    val tick = _currentTick.value ?: return
    if (_connectionState.value !is MarketConnectionState.Connected || _isAuditLoading.value || _isGeminiLoading.value) return
    viewModelScope.launch {
        _isGeminiLoading.value = true
        _geminiSummaryText.value = null
        try {
            _geminiSummaryText.value = GeminiAiService.generateChartSummary24h(
                prefs.geminiApiKey, tick, currentIndicators.value, aiSignalState.value
            )
        } finally { _isGeminiLoading.value = false }
    }
}

fun TradingViewModel.clearGeminiSummary() { _geminiSummaryText.value = null }
