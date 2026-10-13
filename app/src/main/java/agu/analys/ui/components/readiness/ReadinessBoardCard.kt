package agu.analys.ui.components.readiness

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.readiness.ReadinessBoard
import agu.analys.engine.readiness.ReadinessSource
import agu.analys.engine.readiness.ReadinessState
import agu.analys.engine.readiness.TradeHorizon
import agu.analys.ui.components.detail.AnalysisCard
import agu.analys.ui.components.detail.SectionTitle
import agu.analys.ui.theme.TvBorder
import agu.analys.ui.theme.TvCyan
import agu.analys.ui.theme.TvTextMuted
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

/**
 * **SIAP TRADING** — ringkasan papan kesiapan untuk Dashboard.
 *
 * ## Kenapa sekarang mengikuti engine aktif
 *
 * Preferensi engine disimpan pengguna lewat tombol simpan di Pengaturan
 * ([agu.analys.config.StrategyMode]). Sebelum ini kartu dashboard tetap meringkas **ketiga**
 * horizon seolah setara, sehingga saat mode SCALPING aktif kartu ini terlihat "memantau engine
 * lain" — tepat seperti yang dilaporkan pengguna (13 Okt).
 *
 * Sekarang:
 *
 * - horizon engine aktif ditandai dan barisnya berasal dari engine ([ReadinessSource.ENGINE]);
 * - dua horizon lain tetap ditampilkan, tetapi sebagai `PRA-SARING` — penyaring daftar, bukan
 *   keputusan engine, dan labelnya menyebut itu.
 *
 * Daftar penuhnya ada di [agu.analys.ui.screens.ReadinessBoardScreen]; kartu ini sengaja ringkas.
 */
@Composable
fun ReadinessBoardCard(
    board: ReadinessBoard,
    activeHorizon: TradeHorizon,
    loading: Boolean = false,
    onOpenBoard: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnalysisCard(modifier.clickable { onOpenBoard() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("SIAP TRADING", Icons.Default.Radar)
            Spacer(Modifier.weight(1f))
            if (loading) {
                Text("menghitung…", color = TvTextMuted, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Buka papan kesiapan",
                tint = TvTextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Engine aktif", color = TvTextMuted, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            ReadinessSourceBadge(ReadinessSource.ENGINE)
            Spacer(Modifier.width(6.dp))
            Text(
                text = activeHorizon.label.uppercase(),
                color = TvTextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        if (board.isEmpty) {
            Text(
                text = "Belum ada candle yang siap dinilai. Buka papan kesiapan untuk meminta data.",
                color = TvTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            return@AnalysisCard
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TradeHorizon.entries.forEach { horizon ->
                HorizonCountTile(
                    horizon = horizon,
                    ready = board.countOf(horizon, ReadinessState.READY),
                    warming = board.countOf(horizon, ReadinessState.WARMING),
                    total = board.forHorizon(horizon).size,
                    isActive = horizon == activeHorizon,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "Angka di kartu ini hasil pra-saring biaya & tren — bukan keputusan engine. " +
                "Penilaian engine ada di kartu engine pada halaman koin.",
            color = TvTextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )

        // Kandidat engine selalu lebih dulu: itu satu-satunya baris yang sudah dinilai engine.
        val picks = (board.engineRows(activeHorizon) + TradeHorizon.entries.flatMap { board.prescreenRows(it) })
            .filter { it.state == ReadinessState.READY }
            .sortedByDescending { it.score }
            .take(3)

        Spacer(Modifier.height(10.dp))
        if (picks.isEmpty()) {
            Text(
                text = "Belum ada koin yang benar-benar siap masuk. Ini bukan kesalahan tampilan — " +
                    "memang belum ada yang lolos saringan biaya.",
                color = TvTextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        } else {
            Text(
                "KANDIDAT TERATAS",
                color = TvTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
            Spacer(Modifier.height(4.dp))
            picks.forEach { pick ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pick.symbol,
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(pick.horizon.shortLabel, color = TvTextMuted, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${pick.score}", color = readinessStateColor(pick.state), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HorizonCountTile(
    horizon: TradeHorizon,
    ready: Int,
    warming: Int,
    total: Int,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = if (ready > 0) readinessStateColor(ReadinessState.READY) else TvTextMuted
    val borderColor = if (isActive) TvCyan.copy(alpha = 0.6f) else accent.copy(alpha = 0.25f)
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = if (isActive) 0.12f else 0.07f))
            .border(if (isActive) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                horizonIcon(horizon),
                null,
                tint = if (isActive) TvCyan else accent,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(horizon.shortLabel, color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        if (isActive) {
            Spacer(Modifier.height(3.dp))
            Text("ENGINE AKTIF", color = TvCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$ready",
                color = accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            Text("siap", color = TvTextMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text("$warming memanas · $total dinilai", color = TvTextMuted, fontSize = 9.sp)
    }
}

/** Kotak kosong tanpa data — dipakai bila ViewModel belum sempat menghitung. */
@Composable
fun ReadinessBoardCardPlaceholder(
    activeHorizon: TradeHorizon,
    onOpenBoard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth()) {
        ReadinessBoardCard(
            board = ReadinessBoard.EMPTY,
            activeHorizon = activeHorizon,
            loading = true,
            onOpenBoard = onOpenBoard
        )
    }
}
