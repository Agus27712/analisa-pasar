package agu.analys.ui.components.detail.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.components.detail.SectionTitle
import agu.analys.ui.theme.*

@Composable
fun RadarHeaderSection(
    titleHeader: String,
    completed: Int,
    onToggleChecklist: () -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    statusColor: Color? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            SectionTitle(
                titleHeader,
                Icons.Default.Timeline
            )
        }

        Spacer(Modifier.width(8.dp))

        val radarLedColor = statusColor ?: if (completed == 4) TvGreen else TvBlue
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .background(TvSurfaceVariant, RoundedCornerShape(20.dp))
                .border(1.dp, TvBorder, RoundedCornerShape(20.dp))
                .clickable(onClick = onToggleChecklist)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(9.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(radarLedColor.copy(alpha = 0.28f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(6.5.dp)
                        .background(radarLedColor, CircleShape)
                )
            }

            if (statusText != null) {
                Text(
                    text = statusText,
                    color = radarLedColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
