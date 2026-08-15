package agu.analys.ui.components.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.AiProvider
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.AppPreferences

@Composable
fun AiAssistantCard(auditText: String?, auditLoading: Boolean, geminiText: String?, geminiLoading: Boolean, onGroq: () -> Unit, onGemini: () -> Unit, onClearGroq: () -> Unit, onClearGemini: () -> Unit) {
    val provider = AppPreferences(LocalContext.current).aiProvider
    val loading = auditLoading || geminiLoading
    val result = if (provider == AiProvider.GROQ) auditText else geminiText
    val action = if (provider == AiProvider.GROQ) onGroq else onGemini
    AnalysisCard {
        SectionTitle("AI ASISTEN", Icons.Default.AutoAwesome)
        Spacer(Modifier.height(5.dp))
        Text("Provider aktif: ${provider.label}. AI menjelaskan hasil engine, bukan menentukan arah market.", fontSize = 12.sp, color = TvTextSecondary, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = action, enabled = !loading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = TvGreen), shape = RoundedCornerShape(10.dp)) { Text(if (loading) "Menganalisis..." else "Analisa dengan ${provider.label}", color = Color.Black, fontWeight = FontWeight.Bold) }
        result?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, color = TvTextPrimary, lineHeight = 19.sp) }
    }
}
