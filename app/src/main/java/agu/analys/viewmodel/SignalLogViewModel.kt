package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import agu.analys.database.AppDatabase
import agu.analys.database.SignalLogRepository
import agu.analys.database.SignalLogEntity
import agu.analys.model.SignalReliabilitySummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SignalLogViewModel(application: Application) : AndroidViewModel(application) {

    private val signalLogRepository = SignalLogRepository(
        dao = AppDatabase.getInstance().signalLogDao(),
        scope = viewModelScope
    )

    val allSignalLogs: StateFlow<List<SignalLogEntity>> = signalLogRepository.allLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signalReliabilitySummary: StateFlow<SignalReliabilitySummary> = signalLogRepository.reliabilitySummaryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SignalReliabilitySummary())

    private val _auditReportText = MutableStateFlow<String?>(null)
    val auditReportText: StateFlow<String?> = _auditReportText.asStateFlow()

    private val _isAuditLoading = MutableStateFlow(false)
    val isAuditLoading: StateFlow<Boolean> = _isAuditLoading.asStateFlow()

    fun runSignalAuditSummary() {
        viewModelScope.launch {
            _isAuditLoading.value = true
            _auditReportText.value = null
            try {
                val logs = allSignalLogs.value
                if (logs.isEmpty()) {
                    _auditReportText.value = """
                        ### 📋 LAPORAN AUDIT KINERJA SINYAL
                        Belum ada data sinyal untuk diaudit. Jalankan pemindaian pasar atau pantau koin untuk merekam sinyal pertama Anda!
                    """.trimIndent()
                    return@launch
                }

                val total = logs.size
                val tracking = logs.count { it.outcomeStatus == "TRACKING" }
                val wins = logs.count { it.outcomeStatus in listOf("HIT_TP1", "HIT_TP2", "MANUAL_WIN") }
                val losses = logs.count { it.outcomeStatus in listOf("HIT_SL", "MANUAL_LOSS") }
                val other = total - tracking - wins - losses

                val winRate = if ((wins + losses) > 0) (wins.toDouble() / (wins + losses)) * 100.0 else 0.0
                val avgProfit = logs.mapNotNull { it.realizedPnlPct }.filter { it > 0 }.average().let { if (it.isNaN()) 0.0 else it }
                val avgLoss = logs.mapNotNull { it.realizedPnlPct }.filter { it < 0 }.average().let { if (it.isNaN()) 0.0 else it }

                val report = """
                    ### 📋 LAPORAN AUDIT DIAGNOSTIK KINERJA SINYAL
                    
                    Laporan ini dihitung secara dinamis berdasarkan seluruh riwayat sinyal sistem yang terekam pada database lokal aplikasi.
                    
                    - **Total Sinyal Direkam**: $total
                    - **Status Sinyal Aktif (Tracking)**: $tracking sinyal sedang dipantau real-time
                    - **Sinyal Selesai (Target Profit)**: $wins sinyal berhasil mencapai target profit (TP1/TP2)
                    - **Sinyal Selesai (Stop Loss)**: $losses sinyal terkena pembatasan risiko
                    - **Lain-lain (Invalidated/Expired)**: $other sinyal kadaluarsa/batal
                    
                    ---
                    
                    ### 📊 METRIK AKURASI & PROBABILITAS
                    - **Akurasi Win-Rate**: ${String.format(java.util.Locale.US, "%.1f", winRate)}% *(dari ${wins + losses} sinyal terselesaikan)*
                    - **Rata-rata Keuntungan (Profit)**: +${String.format(java.util.Locale.US, "%.2f", avgProfit)}%
                    - **Rata-rata Kerugian (Loss)**: ${String.format(java.util.Locale.US, "%.2f", avgLoss)}%
                    
                    ---
                    
                    ### 💡 PANDUAN STRATEGI & REKOMENDASI TRADING
                    Sistem merekomendasikan untuk tetap menjaga kedisiplinan pada **Rasio Risk-to-Reward (RR)** minimal 1:2. Untuk mode **Scalping agresif**, pastikan momentum volume 24 jam di atas Rp 1 Milyar dan bid wall orderbook tebal untuk menjaga likuiditas keluar-masuk posisi secara cepat dan meminimalkan slippage.
                """.trimIndent()

                _auditReportText.value = report
            } catch (e: Exception) {
                _auditReportText.value = "Gagal memuat laporan audit: ${e.message}"
            } finally {
                _isAuditLoading.value = false
            }
        }
    }

    fun seedSampleLogsIfEmpty() {
        viewModelScope.launch { signalLogRepository.seedSampleLogsIfEmpty() }
    }

    fun deleteLog(id: Long) {
        viewModelScope.launch { signalLogRepository.deleteLog(id) }
    }

    fun clearAllLogs() {
        viewModelScope.launch { signalLogRepository.clearAllLogs() }
    }

    fun resolveLogManually(id: Long, isWin: Boolean, exitPrice: Double? = null, pnlPct: Double? = null, note: String = "") {
        viewModelScope.launch { signalLogRepository.resolveLogManually(id, isWin, exitPrice, pnlPct, note) }
    }

    fun refreshSignalLogs() = signalLogRepository.refreshAndConsolidate()

    fun clearSignalLogs() {
        viewModelScope.launch {
            signalLogRepository.clearAllLogs()
        }
    }
}
