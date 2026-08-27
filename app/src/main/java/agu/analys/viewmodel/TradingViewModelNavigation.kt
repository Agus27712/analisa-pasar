package agu.analys.viewmodel

import agu.analys.model.AppScreen
import agu.analys.model.TradingPair

fun TradingViewModel.navigateTo(screen: AppScreen) {
    if (_currentScreen.value != screen) {
        navigationStack.add(_currentScreen.value)
        _currentScreen.value = screen
    }
}

fun TradingViewModel.openCoinDetail(pair: TradingPair) {
    selectPair(pair)
    navigateTo(AppScreen.DETAIL)
}

fun TradingViewModel.openSimulation(pair: TradingPair? = null) {
    if (pair != null) selectPair(pair)
    navigateTo(AppScreen.SIMULATION_TRADE)
}

fun TradingViewModel.openPortfolio() { navigateTo(AppScreen.PORTFOLIO) }
fun TradingViewModel.openLandscapeChart() { navigateTo(AppScreen.LANDSCAPE_CHART) }
fun TradingViewModel.closeLandscapeChart() { goBack() }
fun TradingViewModel.openSettings() { navigateTo(AppScreen.SETTINGS) }
fun TradingViewModel.openLearning() { navigateTo(AppScreen.LEARNING) }

fun TradingViewModel.goBack() {
    if (navigationStack.isNotEmpty()) {
        _currentScreen.value = navigationStack.removeAt(navigationStack.size - 1)
    } else if (_currentScreen.value != AppScreen.DASHBOARD) {
        _currentScreen.value = AppScreen.DASHBOARD
    }
}
