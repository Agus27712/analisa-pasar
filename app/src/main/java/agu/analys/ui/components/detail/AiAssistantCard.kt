package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import agu.analys.config.AiProvider
import agu.analys.ui.theme.*

/**
 * Popup Dialog Ringkas untuk Analisis AI menggunakan asisten aktif dari Pengaturan (Gemini / Groq).
 */
@Composable
fun AiAssistantDialog(
    aiSignal: String,
    isLoading: Boolean,
    provider: AiProvider,
    onDismiss: () -> Unit,
    onAnalyze: () -> Unit
) {
    // Otomatis picu analisis pertama kali jika belum ada data
    LaunchedEffect(Unit) {
        if (aiSignal.isBlank() && !isLoading) {
            onAnalyze()
        }
    }

    val isLight = LocalAppColors.current == LightAppColors
    val providerName = when (provider) {
        AiProvider.GROQ -> "Groq (OpenAI GPT-OSS)"
        AiProvider.GEMINI -> "Gemini 2.0 Flash"
    }
    val providerColor = when (provider) {
        AiProvider.GROQ -> Color(0xFFFF9800)
        AiProvider.GEMINI -> TvBlue
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 20.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Header Dialog: Title + Active Provider Badge + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(providerColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = providerColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Analisis AI Pasar",
                                color = TvTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Asisten: $providerName",
                                color = providerColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = TvTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Result Box Ringkas & Padat
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 340.dp)
                        .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                        .border(0.8.dp, TvBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (isLoading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = providerColor,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Menganalisis sinyal pasar spot...",
                                color = TvTextSecondary,
                                fontSize = 11.5.sp
                            )
                        }
                    } else if (aiSignal.isNotBlank()) {
                        Text(
                            text = aiSignal,
                            color = TvTextPrimary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    } else {
                        Text(
                            text = "Tekan tombol di bawah untuk meminta analisis AI berdasarkan data real-time, volume, dan indikator Indodax.",
                            color = TvTextSecondary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Single Action Button (Refresh / Analisis Ulang)
                Button(
                    onClick = onAnalyze,
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = providerColor.copy(alpha = 0.2f),
                        contentColor = providerColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, providerColor.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = if (isLoading) Icons.Default.SmartToy else Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isLoading) "Sedang Menganalisis..." else "Perbarui Analisis ($providerName)",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AiAssistantCard(
    auditText: String?,
    auditLoading: Boolean,
    geminiText: String?,
    geminiLoading: Boolean,
    onGroq: () -> Unit,
    onGemini: () -> Unit,
    onClearGroq: () -> Unit,
    onClearGemini: () -> Unit
) {
    val provider = agu.analys.util.AppPreferences(LocalContext.current).aiProvider
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
