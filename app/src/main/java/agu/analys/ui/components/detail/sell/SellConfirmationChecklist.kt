package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.CheckpointStatus
import agu.analys.model.PositionContext
import agu.analys.model.SellCheckpointEvaluator
import agu.analys.model.SellSignalState
import agu.analys.model.TradingCheckpointItem
import agu.analys.ui.theme.*

@Composable
fun SellConfirmationChecklist(
    context: PositionContext,
    sellSignal: SellSignalState,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    val items = remember(context, sellSignal, quoteAsset) {
        SellCheckpointEvaluator.evaluate(context, sellSignal, quoteAsset)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { item ->
            SellChecklistItemRow(item)
        }
    }
}

@Composable
fun SellChecklistItemRow(item: TradingCheckpointItem) {
    val (statusColor, iconVector) = when (item.status) {
        CheckpointStatus.COMPLETED -> Pair(TvGreen, Icons.Default.Check)
        CheckpointStatus.READY -> Pair(TvOrange, Icons.Default.Check)
        CheckpointStatus.ACTIVE -> Pair(TvBlue, Icons.Default.Check)
        CheckpointStatus.WARNING -> Pair(TvRed, Icons.Default.Warning)
        CheckpointStatus.MONITORING -> Pair(TvTextSecondary, Icons.Default.HourglassEmpty)
        CheckpointStatus.LOCKED -> Pair(TvTextSecondary, Icons.Default.HourglassEmpty)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(statusColor.copy(alpha = 0.2f), CircleShape)
                .border(1.dp, statusColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = item.status.name,
                tint = statusColor,
                modifier = Modifier.size(11.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = statusColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
            Text(
                text = item.detail,
                color = TvTextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
        }

        Spacer(Modifier.width(6.dp))

        // Status pill
        Box(
            modifier = Modifier
                .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                .border(0.8.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.5.dp)
        ) {
            Text(
                text = item.status.name,
                color = statusColor,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}
