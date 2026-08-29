package agu.analys.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.AiProvider
import agu.analys.ui.theme.*

@Composable
fun AiAssistantSettings(
    provider: AiProvider,
    groqKey: String,
    geminiKey: String,
    onProviderChange: (AiProvider) -> Unit,
    onKeyChange: (String) -> Unit
) {
    SectionHeader("INTEGRASI AI ASSISTANT")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("PILIH PROVIDER AI", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvBlue)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderChip(
                    label = "GROQ (LLAMA3)",
                    selected = provider == AiProvider.GROQ,
                    modifier = Modifier.weight(1f)
                ) { onProviderChange(AiProvider.GROQ) }
                ProviderChip(
                    label = "GEMINI 1.5",
                    selected = provider == AiProvider.GEMINI,
                    modifier = Modifier.weight(1f)
                ) { onProviderChange(AiProvider.GEMINI) }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "API KEY ${if (provider == AiProvider.GROQ) "GROQ" else "GEMINI"}",
                fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = if (provider == AiProvider.GROQ) groqKey else geminiKey,
                onValueChange = onKeyChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Masukkan API Key...", fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TvBlue,
                    unfocusedBorderColor = TvBorder,
                    focusedTextColor = TvTextPrimary,
                    unfocusedTextColor = TvTextPrimary
                )
            )
        }
    }
}

@Composable
private fun ProviderChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(if (selected) TvBlue.copy(alpha = 0.15f) else TvCardBackground, RoundedCornerShape(6.dp))
            .border(1.dp, if (selected) TvBlue else TvBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(label, color = if (selected) TvBlue else TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}
