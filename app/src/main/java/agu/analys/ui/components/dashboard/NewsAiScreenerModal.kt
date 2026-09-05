package agu.analys.ui.components.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import agu.analys.config.AiProvider
import agu.analys.model.NewsScreenerResult
import agu.analys.model.NewsScreenerUiState
import agu.analys.model.ScreenerCoinPick
import agu.analys.model.TradingPair
import agu.analys.ui.components.MarkdownText
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

/**
 * Dialog Screener AI Pasar dengan frame dan styling yang identik dengan AiAssistantDialog di Detail Screen.
 * Menggunakan hanya 1 AI provider aktif yang dipilih pengguna dari halaman Pengaturan.
 */
@Composable
fun NewsAiScreenerModal(
    state: NewsScreenerUiState,
    provider: AiProvider,
    onDismiss: () -> Unit,
    onRunScreener: (forceRefresh: Boolean) -> Unit,
    onSelectCoin: (TradingPair) -> Unit
) {
    var showRawAnalysis by remember { mutableStateOf(false) }

    // Otomatis picu analisis pertama kali jika state masih Idle atau provider berubah
    LaunchedEffect(provider) {
        if (state is NewsScreenerUiState.Idle) {
            onRunScreener(false)
        } else if (state is NewsScreenerUiState.Success) {
            val isGroqAndStateIsGemini = provider == AiProvider.GROQ && state.result.modelUsed.contains("Gemini", ignoreCase = true)
            val isGeminiAndStateIsGroq = provider == AiProvider.GEMINI && state.result.modelUsed.contains("qwen", ignoreCase = true)
            if (isGroqAndStateIsGemini || isGeminiAndStateIsGroq) {
                onRunScreener(true)
            }
        }
    }

    val providerName = when (provider) {
        AiProvider.GROQ -> "Groq (Qwen 27B)"
        AiProvider.GEMINI -> "Gemini 2.0 Flash"
    }
    val providerColor = when (provider) {
        AiProvider.GROQ -> Color(0xFFFF9800)
        AiProvider.GEMINI -> TvBlue
    }

    val isLoading = state is NewsScreenerUiState.Loading

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 20.dp)
                .testTag("news_ai_screener_dialog"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Header Dialog: Title + Active Provider Badge + Close (Persis Detail Screen)
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
                                text = "Screener AI Berita",
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

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = TvTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Result Box Ringkas & Padat (Persis Detail Screen)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 460.dp)
                        .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                        .border(0.8.dp, TvBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    when (state) {
                        is NewsScreenerUiState.Loading -> {
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
                                    text = state.stage,
                                    color = TvTextSecondary,
                                    fontSize = 11.5.sp
                                )
                            }
                        }

                        is NewsScreenerUiState.Error -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = TvRed,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = state.message,
                                    color = TvTextSecondary,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        is NewsScreenerUiState.Idle -> {
                            Text(
                                text = "Tekan tombol di bawah untuk menyaring berita kripto terkini dan menemukan koin Indodax berpotensi naik.",
                                color = TvTextSecondary,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            )
                        }

                        is NewsScreenerUiState.Success -> {
                            ScreenerResultsContent(
                                result = state.result,
                                providerColor = providerColor,
                                showRawAnalysis = showRawAnalysis,
                                onToggleRaw = { showRawAnalysis = !showRawAnalysis },
                                onSelectCoin = { pick ->
                                    val pair = TradingPair.fromCustomSymbol(pick.baseSymbol, "IDR")
                                    onSelectCoin(pair)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Single Action Button (Refresh / Analisis Ulang - Persis Detail Screen)
                Button(
                    onClick = { onRunScreener(true) },
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
private fun ScreenerResultsContent(
    result: NewsScreenerResult,
    providerColor: Color,
    showRawAnalysis: Boolean,
    onToggleRaw: () -> Unit,
    onSelectCoin: (ScreenerCoinPick) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            // Header Info Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                    .border(0.6.dp, TvBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ditemukan ${result.picks.size} Koin Indodax Berpotensi Naik",
                    color = TvGreen,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Spot Indodax Only",
                    color = TvTextMuted,
                    fontSize = 9.sp
                )
            }
        }

        if (result.picks.isEmpty()) {
            item {
                Text(
                    text = "Belum ada koin Indodax dengan katalis kuat yang terdeteksi pada berita terkini.",
                    color = TvTextSecondary,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        } else {
            items(result.picks, key = { it.baseSymbol }) { pick ->
                ScreenerCoinCard(pick = pick, providerColor = providerColor, onSelect = { onSelectCoin(pick) })
            }
        }

        // Akordeon Transkrip Lengkap Analisis AI (Markdown)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TvSurface),
                shape = RoundedCornerShape(6.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(listOf(TvBorder, TvBorder))
                )
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggleRaw),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🤖 Transkrip Lengkap Analisis AI",
                            color = TvTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            if (showRawAnalysis) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = TvTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    AnimatedVisibility(visible = showRawAnalysis) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            MarkdownText(
                                markdown = result.rawAnalysis,
                                textColor = TvTextPrimary,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenerCoinCard(
    pick: ScreenerCoinPick,
    providerColor: Color,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(listOf(TvBorder, TvBorder))
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Row 1: Symbol + Badge Indodax + Price & 24h Change
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color(0xFF1E293B), CircleShape)
                            .border(0.8.dp, providerColor.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = pick.baseSymbol.take(3),
                            color = TvTextPrimary,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "${pick.baseSymbol}/IDR",
                                color = TvTextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF064E3B), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 3.dp, vertical = 0.5.dp)
                            ) {
                                Text("INDODAX", color = TvGreenLight, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            text = pick.sectorNarrative,
                            color = TvBlueSoft,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    if (pick.currentPrice > 0) {
                        Text(
                            text = PriceFormatter.formatPrice(pick.currentPrice),
                            color = TvTextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black
                        )
                        val chColor = if (pick.change24h >= 0) TvGreen else TvRed
                        val sign = if (pick.change24h >= 0) "+" else ""
                        Text(
                            text = "$sign${PriceFormatter.formatPercentage(pick.change24h)}",
                            color = chColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1E3A8A), RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(pick.sentimentGrade, color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Row 2: Katalis Utama
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(5.dp))
                    .border(0.5.dp, TvBorder, RoundedCornerShape(5.dp))
                    .padding(6.dp)
            ) {
                Column {
                    Text(
                        text = "🔥 Katalis Berita:",
                        color = TvAmber,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = pick.mainCatalyst,
                        color = TvTextPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 14.sp
                    )
                }
            }

            // Row 3: Alasan Penguatan
            if (pick.reasons.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    pick.reasons.forEach { reason ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("•", color = TvGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = reason,
                                color = TvTextSecondary,
                                fontSize = 10.sp,
                                lineHeight = 13.5.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Row 4: Action "Buka Detail & Chart"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Validitas: ${pick.validityGrade}",
                    color = TvTextMuted,
                    fontSize = 9.sp
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(TvBlue.copy(alpha = 0.2f))
                        .border(0.5.dp, TvBlue, RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "Buka Detail & Chart",
                        color = TvBlueSoft,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = TvBlueSoft,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}
