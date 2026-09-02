package agu.analys.engine.badge

import agu.analys.config.StrategyMode
import agu.analys.engine.officedaily.OfficeDailyScreener
import agu.analys.engine.secondwave.SecondWaveEvaluator
import agu.analys.model.BadgeType
import agu.analys.model.CoinBadge
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair

object CoinBadgeEvaluator {

    /**
     * Evaluasi badge per koin. Active strategy diprioritaskan.
     * Max [maxBadges] (default 4). Badge beda-beda tergantung setup yang cocok.
     */
    fun evaluateBadges(
        pair: TradingPair,
        tick: MarketTick?,
        activeStrategy: StrategyMode = StrategyMode.SCALPING,
        isSetupReady: Boolean = false,
        maxBadges: Int = 4
    ): List<CoinBadge> {
        if (tick == null || tick.price <= 0.0) return emptyList()

        val badges = mutableListOf<CoinBadge>()
        val change = if (tick.change24h.isFinite()) tick.change24h else 0.0
        val volume = tick.volume24h
        val isUsdt = pair.quoteAsset.equals("USDT", true) || pair.quoteAsset.equals("USD", true)

        val volThresholdHigh = if (isUsdt) 5_000_000.0 else 25_000_000_000.0
        val volThresholdMed = if (isUsdt) 1_000_000.0 else 5_000_000_000.0
        val volThresholdMin = if (isUsdt) 200_000.0 else 1_000_000_000.0

        // Strategy badges
        val officeScore = OfficeDailyScreener.evaluateFast(tick)
        if (officeScore.isQualified) {
            val prio = if (activeStrategy == StrategyMode.OFFICE_DAILY) 0 else BadgeType.OFFICEDAILY.defaultPriority
            badges.add(CoinBadge(BadgeType.OFFICEDAILY, priority = prio, description = officeScore.summary))
        }

        val secondWaveScore = SecondWaveEvaluator.evaluateFast(tick, tick.high24h, tick.low24h)
        if (secondWaveScore.isQualified) {
            val prio = if (activeStrategy == StrategyMode.SECOND_WAVE) 0 else BadgeType.SECONDWAVE.defaultPriority
            badges.add(CoinBadge(BadgeType.SECONDWAVE, priority = prio, description = secondWaveScore.summary))
        }

        val isSwingCandidate = volume >= volThresholdMin && change in -3.0..8.0
        if (isSwingCandidate) {
            val prio = if (activeStrategy == StrategyMode.SWING) 0 else BadgeType.SWING.defaultPriority
            badges.add(CoinBadge(BadgeType.SWING, priority = prio, description = "Setup Swing terdeteksi"))
        }

        val isScalpingCandidate =
            volume >= volThresholdMin && (change >= 2.0 || change <= -2.0 || volume >= volThresholdMed)
        if (isScalpingCandidate) {
            val prio = if (activeStrategy == StrategyMode.SCALPING) 0 else BadgeType.SCALPING.defaultPriority
            badges.add(CoinBadge(BadgeType.SCALPING, priority = prio, description = "Momentum Scalping aktif"))
        }

        // READY — heuristic internal + flag eksternal
        val strongMomentumReady =
            isScalpingCandidate && change >= 2.5 && volume >= volThresholdMed &&
                (activeStrategy == StrategyMode.SCALPING || activeStrategy == StrategyMode.SECOND_WAVE)
        val officeReady =
            officeScore.isQualified && activeStrategy == StrategyMode.OFFICE_DAILY && change in 0.5..8.0
        val secondWaveReady =
            secondWaveScore.isQualified && activeStrategy == StrategyMode.SECOND_WAVE &&
                secondWaveScore.drawdownPct in 15.0..55.0

        if (isSetupReady || strongMomentumReady || officeReady || secondWaveReady) {
            badges.add(CoinBadge(BadgeType.READY, priority = BadgeType.READY.defaultPriority, description = "Siap Entry"))
        }

        if (volume >= volThresholdMed && change >= 3.0) {
            badges.add(CoinBadge(BadgeType.HOT, priority = BadgeType.HOT.defaultPriority, description = "Aktivitas Volume & Kenaikan Tinggi"))
        }
        if (change >= 6.0) {
            badges.add(
                CoinBadge(
                    BadgeType.PUMP,
                    priority = BadgeType.PUMP.defaultPriority,
                    description = "+${String.format(java.util.Locale.US, "%.1f", change)}% dalam 24h"
                )
            )
        }
        if (change <= -6.0) {
            badges.add(
                CoinBadge(
                    BadgeType.DUMP,
                    priority = BadgeType.DUMP.defaultPriority,
                    description = "${String.format(java.util.Locale.US, "%.1f", change)}% dalam 24h"
                )
            )
        }
        if (volume >= volThresholdHigh) {
            badges.add(CoinBadge(BadgeType.VOL24, priority = BadgeType.VOL24.defaultPriority, description = "Volume 24H sangat tinggi"))
        }

        return badges
            .distinctBy { it.type }
            .sortedBy { it.priority }
            .take(maxBadges.coerceAtLeast(1))
    }
}
