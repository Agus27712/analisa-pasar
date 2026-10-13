package agu.analys.ui.components.readiness

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.readiness.ReadinessCheck
import agu.analys.engine.readiness.ReadinessSource
import agu.analys.engine.readiness.ReadinessState
import agu.analys.engine.readiness.TradeHorizon
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvBlue
import agu.analys.ui.theme.TvBorder
import agu.analys.ui.theme.TvCyan
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextMuted
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

/**
 * Perkakas tampilan bersama untuk papan kesiapan (TEAM 2).
 *
 * Semua warna dan label diturunkan dari [ReadinessState] / [TradeHorizon] — tidak ada pemetaan
 * status yang ditulis ulang di layar mana pun. Ini menjaga aturan yang sama seperti perbaikan
 * radar sebelumnya: **satu sumber kebenaran untuk kesimpulan status**.
 */

@Composable
fun readinessStateColor(state: ReadinessState): Color = when (state) {
    ReadinessState.HOLDING -> TvBlue
    ReadinessState.READY -> TvGreen
    ReadinessState.WARMING -> TvCyan
    ReadinessState.WATCH -> TvAmber
    ReadinessState.BLOCKED -> TvRed
    ReadinessState.NO_DATA -> TvTextMuted
}

/**
 * Penanda **dari mana** sebuah baris kesiapan berasal.
 *
 * Ini bukan hiasan. Tanpa penanda ini, pembaca tidak punya cara membedakan "engine yang sedang
 * dipakai sudah menilai dan bilang begini" dari "saringan biaya sedang menduga begini" — dan
 * itulah yang membuat papan terasa bertentangan dengan kartu engine (laporan 13 Okt).
 */
@Composable
fun ReadinessSourceBadge(
    source: ReadinessSource,
    modifier: Modifier = Modifier
) {
    val color = when (source) {
        ReadinessSource.ENGINE -> TvGreen
        ReadinessSource.PRESCREEN -> TvTextMuted
    }
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = source.label,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
    }
}

fun horizonIcon(horizon: TradeHorizon): ImageVector = when (horizon) {
    TradeHorizon.SCALP -> Icons.Default.Bolt
    TradeHorizon.INTRADAY -> Icons.Default.Timer
    TradeHorizon.SWING -> Icons.Default.Timeline
}

/** Chip status ringkas — dipakai di daftar maupun header. */
@Composable
fun ReadinessStateChip(
    state: ReadinessState,
    modifier: Modifier = Modifier
) {
    val color = readinessStateColor(state)
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = state.label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp
        )
    }
}

/**
 * Penanda kejujuran: horizon ini **belum** diuji keluar-sampel.
 *
 * Ditampilkan menempel pada horizon (bukan disembunyikan di pengaturan) supaya pengguna tidak
 * menyimpulkan INTRADAY/SWING setara dengan SCALP yang sudah diuji data.
 */
@Composable
fun UnvalidatedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(TvAmber.copy(alpha = 0.12f))
            .border(1.dp, TvAmber.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "BELUM DIVALIDASI",
            color = TvAmber,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
    }
}

/** Bilah skor 0..100. */
@Composable
fun ReadinessScoreBar(
    score: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(TvBorder.copy(alpha = 0.5f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth((score.coerceIn(0, 100)) / 100f)
                .background(color, RoundedCornerShape(3.dp))
        )
    }
}

/** Satu baris syarat: tanda centang/silang + label + detail dari engine. */
@Composable
fun ReadinessCheckRow(
    check: ReadinessCheck,
    modifier: Modifier = Modifier
) {
    val color = if (check.ok) TvGreen else TvTextMuted
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (check.ok) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(11.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = check.label,
                color = if (check.ok) TvTextPrimary else TvTextSecondary,
                fontSize = 12.sp,
                fontWeight = if (check.ok) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = check.detail,
                color = TvTextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

/** Baris label ↔ nilai untuk rencana trade. */
@Composable
fun PlanRow(
    label: String,
    value: String,
    valueColor: Color = TvTextPrimary,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TvTextSecondary, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
