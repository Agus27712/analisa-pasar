package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

val AnalysisCardBg: Color @Composable get() = TvCardBackground
val AnalysisBorder: Color @Composable get() = TvBorder
val WarningAmber: Color @Composable get() = TvAmber
val InfoBlue: Color @Composable get() = TvBlue
val TvGold: Color @Composable get() = TvAmber

/** Shared spacing for detail cards — keep hierarchy consistent. */
object AnalysisSpacing {
    val cardPadding = 16.dp
    val sectionGap = 10.dp
    val rowGap = 6.dp
}

@Composable
fun AnalysisCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AnalysisCardBg, RoundedCornerShape(16.dp))
            .border(1.dp, AnalysisBorder, RoundedCornerShape(16.dp))
            .padding(AnalysisSpacing.cardPadding),
        content = content
    )
}

@Composable
fun SectionTitle(text: String, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                icon, null,
                tint = when {
                    text.contains("TEKNIKAL", ignoreCase = true) -> TvBlue
                    text.contains("LEVEL", ignoreCase = true) -> TvBlue
                    text.contains("PROGRESS", ignoreCase = true) -> TvBlue
                    else -> TvGreen
                },
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TvTextPrimary,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
fun AnalysisDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(TvBorder)
    )
}

@Composable
fun IconTextRow(icon: ImageVector, text: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp)
    }
}

@Composable
fun CaptionHint(text: String) {
    Text(text, fontSize = 12.sp, color = TvTextSecondary, lineHeight = 17.sp)
}
