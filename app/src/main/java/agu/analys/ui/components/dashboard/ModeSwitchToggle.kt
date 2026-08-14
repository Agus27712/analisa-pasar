package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextSecondary

@Composable
fun ModeSwitchToggle(
    isScalping: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { onToggle(false) },
            modifier = Modifier.weight(1f).height(38.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!isScalping) TvGreen else DashboardColors.Card
            ),
            shape = RoundedCornerShape(11.dp),
            border = if (!isScalping) null else BorderStroke(1.dp, DashboardColors.Border),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(
                "Mode Swing",
                color = if (!isScalping) Color.Black else TvTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
        Button(
            onClick = { onToggle(true) },
            modifier = Modifier.weight(1f).height(38.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isScalping) TvGreen else DashboardColors.Card
            ),
            shape = RoundedCornerShape(11.dp),
            border = if (isScalping) null else BorderStroke(1.dp, DashboardColors.Border),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(
                "Mode Scalping",
                color = if (isScalping) Color.Black else TvTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
    }
}
