package agu.analys.ui.components.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import agu.analys.model.PriceAlert
import agu.analys.model.Timeframe
import agu.analys.ui.theme.*

@Composable
fun DetailControlsRow(
    selectedTimeframe: Timeframe,
    onSelectTimeframe: (Timeframe) -> Unit,
    priceAlerts: List<PriceAlert>,
    isFavorite: Boolean,
    onOpenAlerts: () -> Unit,
    onOpenPortfolio: () -> Unit,
    onOpenAiAssistant: () -> Unit,
    onOpenSimulation: () -> Unit,
    onOpenLearning: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timeframe Chips (Grup Kiri)
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(Timeframe.M1, Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1).forEach { tf ->
                val isSelected = selectedTimeframe == tf
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) TvBlue.copy(alpha = 0.15f) else TvSurface)
                        .border(1.dp, if (isSelected) TvBlue else TvBorder, RoundedCornerShape(8.dp))
                        .clickable { onSelectTimeframe(tf) }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tf.label.uppercase(),
                        color = if (isSelected) TvBlue else TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // Quick Action Icons (Grup Kanan)
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val activeAlertCount = priceAlerts.count { it.isEnabled && !it.isTriggered }

            // 1. Alert Icon Button
            DetailQuickActionButton(
                icon = if (activeAlertCount > 0) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                tint = if (activeAlertCount > 0) TvBlue else TvTextSecondary,
                bgColor = if (activeAlertCount > 0) TvBlue.copy(alpha = 0.15f) else TvSurface,
                borderColor = if (activeAlertCount > 0) TvBlue.copy(alpha = 0.5f) else TvBorder,
                contentDescription = "Alert",
                onClick = onOpenAlerts
            )

            // 2. Portofolio Shortcut Icon Button
            DetailQuickActionButton(
                icon = Icons.Default.AccountBalanceWallet,
                tint = TvGreen,
                bgColor = TvGreen.copy(alpha = 0.12f),
                borderColor = TvGreen.copy(alpha = 0.4f),
                contentDescription = "Portofolio",
                onClick = onOpenPortfolio
            )

            // 3. AI Assistant Icon Button
            DetailQuickActionButton(
                icon = Icons.Default.AutoAwesome,
                tint = TvBlue,
                bgColor = TvBlue.copy(alpha = 0.12f),
                borderColor = TvBlue.copy(alpha = 0.4f),
                contentDescription = "AI Analisa",
                onClick = onOpenAiAssistant
            )

            // 4. Simulasi Icon Button
            DetailQuickActionButton(
                icon = Icons.AutoMirrored.Filled.CompareArrows,
                tint = TvGreen,
                bgColor = TvGreen.copy(alpha = 0.12f),
                borderColor = TvGreen.copy(alpha = 0.4f),
                contentDescription = "Simulasi",
                onClick = onOpenSimulation
            )

            // 5. Belajar / Edukasi Icon Button
            DetailQuickActionButton(
                icon = Icons.Default.MenuBook,
                tint = TvBlue,
                bgColor = TvBlue.copy(alpha = 0.12f),
                borderColor = TvBlue.copy(alpha = 0.4f),
                contentDescription = "Belajar",
                onClick = onOpenLearning
            )

            // 6. Favorit Icon Button
            DetailQuickActionButton(
                icon = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                tint = if (isFavorite) TvAmber else TvTextSecondary,
                bgColor = if (isFavorite) TvAmber.copy(alpha = 0.12f) else TvSurface,
                borderColor = if (isFavorite) TvAmber.copy(alpha = 0.4f) else TvBorder,
                contentDescription = "Favorit",
                onClick = onToggleFavorite
            )
        }
    }
}

@Composable
fun DetailQuickActionButton(
    icon: ImageVector,
    tint: Color,
    bgColor: Color,
    borderColor: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
