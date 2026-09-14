package agu.analys.viewmodel

import agu.analys.config.MarketDataSource
import agu.analys.config.ScalpingSensitivity
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.model.ChartStyle
import agu.analys.model.Timeframe
import agu.analys.ui.animation.PriceAnimationMode
import agu.analys.ui.theme.AccentColorPreset
import agu.analys.ui.theme.AnimationSpeed
import agu.analys.ui.theme.CandleColorStyle
import agu.analys.ui.theme.ThemeStyle

/**
 * Extension for TradingViewModel dealing with user settings, themes, notifications,
 * strategy configurations, and chart options.
 */

fun TradingViewModel.getGroqApiKey() = prefs.groqApiKey
fun TradingViewModel.saveGroqApiKey(key: String) { prefs.groqApiKey = key }
fun TradingViewModel.getGeminiApiKey() = prefs.geminiApiKey
fun TradingViewModel.saveGeminiApiKey(key: String) { prefs.geminiApiKey = key }

fun TradingViewModel.setMarketDataSource(source: MarketDataSource) {
    _marketDataSource.value = source
    prefs.marketDataSource = source
    refreshWorthCoinsFromMarket()
}

fun TradingViewModel.setStrategyMode(mode: StrategyMode) {
    _strategyMode.value = mode
    prefs.strategyMode = mode
    val scalpingEnabled = mode == StrategyMode.SCALPING
    _isScalpingMode.value = scalpingEnabled
    prefs.isScalpingMode = scalpingEnabled
    engine.strategyMode = mode
    engine.isScalpingMode = scalpingEnabled
    engine.scalpingSensitivity = prefs.scalpingSensitivity
    engine.tradingFees = prefs.tradingFees
    
    if (mode == StrategyMode.SCALPING || mode == StrategyMode.SECOND_WAVE) {
        agu.analys.util.MtfCacheManager.setActiveSymbol(_selectedPair.value.symbol)
    }
    
    marketDataCoordinator.startMarketPolling(_selectedPair.value, _selectedTimeframe.value)
    val tick = marketDataCoordinator.currentTick.value
    if (tick != null) {
        engine.resetForOffline()
        engine.onTickUpdate(tick)
    }
    refreshWorthCoinsFromMarket()
}

fun TradingViewModel.setScalpingMode(enabled: Boolean) {
    setStrategyMode(if (enabled) StrategyMode.SCALPING else StrategyMode.SECOND_WAVE)
}

fun TradingViewModel.setScalpingSensitivity(sensitivity: ScalpingSensitivity) {
    if (_scalpingSensitivity.value == sensitivity) return
    _scalpingSensitivity.value = sensitivity
    prefs.scalpingSensitivity = sensitivity
    engine.scalpingSensitivity = sensitivity
    val tick = marketDataCoordinator.currentTick.value
    val candles = marketDataCoordinator.recentCandles.value
    if (tick != null && candles.isNotEmpty()) {
        engine.resetForOffline()
        engine.onTickUpdate(tick)
    }
}

fun TradingViewModel.updateTradingFees(fees: TradingFeeConfig) {
    prefs.tradingFees = fees
    _tradingFees.value = fees
    engine.tradingFees = fees
}

fun TradingViewModel.setDarkTheme(enabled: Boolean) {
    prefs.isDarkTheme = enabled
    _isDarkTheme.value = enabled
    val targetStyle = if (enabled) ThemeStyle.DARK_NAVY else ThemeStyle.LIGHT_CLEAN
    _themeStyle.value = targetStyle
    prefs.themeStyle = targetStyle
}

fun TradingViewModel.setThemeStyle(style: ThemeStyle) {
    prefs.themeStyle = style
    _themeStyle.value = style
    val isDark = style != ThemeStyle.LIGHT_CLEAN
    prefs.isDarkTheme = isDark
    _isDarkTheme.value = isDark
}

fun TradingViewModel.setAccentColorPreset(preset: AccentColorPreset) {
    prefs.accentColorPreset = preset
    _accentColorPreset.value = preset
}

fun TradingViewModel.setCandleColorStyle(style: CandleColorStyle) {
    prefs.candleColorStyle = style
    _candleColorStyle.value = style
}

fun TradingViewModel.setAnimationSpeed(speed: AnimationSpeed) {
    prefs.animationSpeed = speed
    _animationSpeed.value = speed
}

fun TradingViewModel.setPriceAnimationMode(mode: PriceAnimationMode) {
    prefs.priceAnimationMode = mode
    _priceAnimationMode.value = mode
}

fun TradingViewModel.setPriceTickPulseEnabled(enabled: Boolean) {
    prefs.isPriceTickPulseEnabled = enabled
    _isPriceTickPulseEnabled.value = enabled
}

fun TradingViewModel.setSmoothChartEnabled(enabled: Boolean) {
    prefs.isSmoothChartEnabled = enabled
    _isSmoothChartEnabled.value = enabled
}

fun TradingViewModel.setNotificationsEnabled(enabled: Boolean) {
    prefs.isNotificationsEnabled = enabled
    _isNotificationsEnabled.value = enabled
    updateForegroundServiceState()
}

fun TradingViewModel.setNotifyCandidateBuyEnabled(enabled: Boolean) {
    prefs.isNotifyCandidateBuyEnabled = enabled
    _isNotifyCandidateBuyEnabled.value = enabled
}

fun TradingViewModel.setNotifyPriceAlertsEnabled(enabled: Boolean) {
    prefs.isNotifyPriceAlertsEnabled = enabled
    _isNotifyPriceAlertsEnabled.value = enabled
}

fun TradingViewModel.setNotifyTrailingStopEnabled(enabled: Boolean) {
    prefs.isNotifyTrailingStopEnabled = enabled
    _isNotifyTrailingStopEnabled.value = enabled
}

fun TradingViewModel.setRealSimSyncEnabled(enabled: Boolean) {
    prefs.isRealSimSyncEnabled = enabled
    _isRealSimSyncEnabled.value = enabled
    refreshSpotPosition()
    simCoordinator.refresh()
    updateForegroundServiceState()
}

fun TradingViewModel.toggleSimpleChart() { _useSimpleChart.value = !_useSimpleChart.value }

fun TradingViewModel.selectTimeframe(tf: Timeframe) {
    if (_selectedTimeframe.value == tf) return
    _selectedTimeframe.value = tf
    marketDataCoordinator.switchTimeframe(_selectedPair.value, tf)
}

fun TradingViewModel.selectChartStyle(style: ChartStyle) { _selectedChartStyle.value = style }

fun TradingViewModel.toggleChartExpanded() { _isChartExpanded.value = !_isChartExpanded.value }

fun TradingViewModel.setUiPriceThrottleMs(ms: Long) {
    marketDataCoordinator.setPriceFeedThrottleMs(ms)
}

fun TradingViewModel.retryConnection() {
    marketDataCoordinator.startMarketPolling(_selectedPair.value, _selectedTimeframe.value)
    refreshWorthCoinsFromMarket()
    agu.analys.util.MtfCacheManager.setActiveSymbol(_selectedPair.value.symbol)
}

fun TradingViewModel.simulateDisconnect() {
    marketDataCoordinator.markOffline("Mode offline: koneksi dihentikan manual.")
}
