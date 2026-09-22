package agu.analys.ui.components.detail.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.StrategyMode
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.ui.theme.*

@Composable
fun RadarConfirmationChecklist(
    mtf: ScalpingMtfSnapshot,
    strategyMode: StrategyMode,
    modifier: Modifier = Modifier
) {
    val checkpoints = remember(mtf) { mtf.resolvedCheckpoints() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        checkpoints.forEach { cp ->
            RadarChecklistItem(
                stepNumber = cp.number,
                label = "${cp.number}. ${cp.label}",
                metricValue = cp.metricValue,
                isOk = cp.isOk,
                detail = cp.detail
            )
        }
    }
}

@Composable
fun RadarChecklistItem(
    stepNumber: Int,
    label: String,
    metricValue: String = "",
    isOk: Boolean,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(
                    if (isOk) TvGreen.copy(alpha = 0.2f) else TvSurfaceVariant,
                    CircleShape
                )
                .border(
                    1.dp,
                    if (isOk) TvGreen else TvBorder,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isOk) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "OK",
                    tint = TvGreen,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.HourglassEmpty,
                    contentDescription = "Pending",
                    tint = TvBlue,
                    modifier = Modifier.size(10.dp)
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = label,
                    color = if (isOk) TvGreen else TvTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = if (isOk) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false).basicMarquee()
                )
                if (metricValue.isNotBlank()) {
                    Text(
                        text = "· $metricValue",
                        color = if (isOk) TvGreen else TvAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    color = TvTextSecondary,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
            }
        }

        Box(
            modifier = Modifier
                .background(
                    if (isOk) TvGreen.copy(alpha = 0.15f) else TvSurfaceVariant,
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (isOk) "LOLOS" else "SCAN",
                color = if (isOk) TvGreen else TvBlue,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
