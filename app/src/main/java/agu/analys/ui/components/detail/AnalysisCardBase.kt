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
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary

val AnalysisCardBg = Color(0xFF0D1722)
val AnalysisBorder = Color(0xFF1A3347)
val WarningAmber = Color(0xFFFFB300)
val InfoBlue = Color(0xFF2196F3)
val TvGold = Color(0xFFFFD54A)

@Composable
fun AnalysisCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AnalysisCardBg, RoundedCornerShape(16.dp))
            .border(1.dp, AnalysisBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun SectionTitle(text: String, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                icon, null,
                tint = if (text == "DETAIL TEKNIKAL") Color(0xFF6FB8FF) else TvGreen,
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary, letterSpacing = 0.5.sp)
    }
}

@Composable
fun IconTextRow(icon: ImageVector, text: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp)
    }
}
