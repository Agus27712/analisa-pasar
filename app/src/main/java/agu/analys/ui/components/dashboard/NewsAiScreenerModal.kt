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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import agu.analys.config.AiProvider
import agu.analys.model.NewsArticle
import agu.analys.model.NewsScreenerResult
import agu.analys.model.NewsScreenerUiState
import agu.analys.model.ScreenerCoinPick
import agu.analys.model.TradingPair
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun NewsAiScreenerModal(
    state: NewsScreenerUiState,
    preferredProvider: AiProvider,
    onDismiss: () -> Unit,
    onRunScreener: (forceRefresh: Boolean, provider: AiProvider?) -> Unit,
    onSelectCoin: (TradingPair) -> Unit
) {
    var activeProvider by remember { mutableStateOf(preferredProvider) }
    var showRawAnalysis by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (state is NewsScreenerUiState.Idle) {
            onRunScreener(false, activeProvider)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 12.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .testTag("news_ai_screener_dialog"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TvSurface),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF6366F1), Color(0xFF38BDF8), TvBorder)
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                ) {
                    // Header Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF4F46E5), Color(0xFF06B6D4))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("AI", color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            }
                            Column {
                                Text(
                                    text = "AI News Catalyst Screener",
                                    color = TvTextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "Screener Awal Koin Indodax (Spot Only)",
                                    color = TvTextSecondary,
                                    fontSize = 10.5.sp
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Model & Source Toggle Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(TvSurfaceVariant)
                            .border(0.8.dp, TvBorder, RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ProviderChip(
                                label = "Groq (Qwen 757)",
                                isSelected = activeProvider == AiProvider.GROQ,
                                onClick = {
                                    activeProvider = AiProvider.GROQ
                                    onRunScreener(false, AiProvider.GROQ)
                                }
                            )
                            ProviderChip(
                                label = "Gemini Flash (757)",
                                isSelected = activeProvider == AiProvider.GEMINI,
                                onClick = {
                                    activeProvider = AiProvider.GEMINI
                                    onRunScreener(false, AiProvider.GEMINI)
                                }
                            )
                        }

                        IconButton(
                            onClick = { onRunScreener(true, activeProvider) },
                            modifier = Modifier.size(28.dp),
                            enabled = state !is NewsScreenerUiState.Loading
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Berita",
                                tint = TvBlueSoft,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Content Area
                    when (state) {
                        is NewsScreenerUiState.Loading -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = TvBlue,
                                        modifier = Modifier.size(36.dp),
                                        strokeWidth = 3.dp
                                    )
                                    Text(
                                        text = state.stage,
                                        color = TvTextPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Sumber: Cointelegraph, Google News Crypto, Coinvestasi",
                                        color = TvTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }

                        is NewsScreenerUiState.Error -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = TvRed, modifier = Modifier.size(32.dp))
                                    Text(
                                        text = state.message,
                                        color = TvTextSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                    Button(
                                        onClick = { onRunScreener(true, activeProvider) },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Coba Lagi", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        is NewsScreenerUiState.Idle -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = { onRunScreener(false, activeProvider) },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("⚡ Mulai Analisis Berita Koin", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        is NewsScreenerUiState.Success -> {
                            ScreenerResultsList(
                                result = state.result,
                                showRawAnalysis = showRawAnalysis,
                                onToggleRaw = { showRawAnalysis = !showRawAnalysis },
                                onSelectCoin = { pick ->
                                    val pair = TradingPair.fromCustomSymbol(pick.baseSymbol, "IDR")
                                    onSelectCoin(pair)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) TvBlue.copy(alpha = 0.25f) else Color.Transparent)
            .then(
                if (isSelected) Modifier.border(0.8.dp, TvBlue, RoundedCornerShape(6.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) TvBlueSoft else TvTextSecondary,
            fontSize = 10.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun ScreenerResultsList(
    result: NewsScreenerResult,
    showRawAnalysis: Boolean,
    onToggleRaw: () -> Unit,
    onSelectCoin: (ScreenerCoinPick) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            // Info Header Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                    .border(0.7.dp, TvBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ditemukan ${result.picks.size} Koin Indodax Berpotensi Naik",
                    color = TvGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = result.providerUsed,
                    color = TvTextMuted,
                    fontSize = 9.5.sp
                )
            }
        }

        if (result.picks.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Tidak ada koin Indodax dengan katalis kuat yang terdeteksi pada feed saat ini.",
                            color = TvTextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        } else {
            items(result.picks, key = { it.baseSymbol }) { pick ->
                CoinPickCard(pick = pick, onSelect = { onSelectCoin(pick) })
            }
        }

        // Accordion: Transkrip Lengkap Analisis AI (Maks 757 token)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                shape = RoundedCornerShape(8.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(TvBorder, TvBorder)))
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggleRaw),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🤖 Transkrip Lengkap Analisis AI (${result.modelUsed})",
                            color = TvTextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            if (showRawAnalysis) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = TvTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(visible = showRawAnalysis) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                text = result.rawAnalysis,
                                color = TvTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun CoinPickCard(
    pick: ScreenerCoinPick,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                listOf(TvBorder, TvBorder)
            )
        )
    ) {
        Column(modifier = Modifier.padding(11.dp)) {
            // Top Row: Coin Badge + Live Price + Sentiment Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color(0xFF1E293B), CircleShape)
                            .border(1.dp, TvBlueSoft.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = pick.baseSymbol.take(3),
                            color = TvTextPrimary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "${pick.baseSymbol}/IDR",
                                color = TvTextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF064E3B), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text("INDODAX SPOT", color = TvGreenLight, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            text = pick.sectorNarrative,
                            color = TvBlueSoft,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    if (pick.currentPrice > 0) {
                        Text(
                            text = PriceFormatter.formatPrice(pick.currentPrice),
                            color = TvTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                        val chColor = if (pick.change24h >= 0) TvGreen else TvRed
                        val sign = if (pick.change24h >= 0) "+" else ""
                        Text(
                            text = "$sign${PriceFormatter.formatPercentage(pick.change24h)}",
                            color = chColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1E3A8A), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(pick.sentimentGrade, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Katalis Utama Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                    .border(0.6.dp, TvBorder, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Column {
                    Text(
                        text = "🔥 Katalis Berita:",
                        color = TvAmber,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = pick.mainCatalyst,
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 15.sp
                    )
                }
            }

            // Alasan Penguatan Bullet Points
            if (pick.reasons.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    pick.reasons.forEach { reason ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text("•", color = TvGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = reason,
                                color = TvTextSecondary,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bottom Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Validitas: ${pick.validityGrade}",
                    color = TvTextMuted,
                    fontSize = 9.5.sp
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(TvBlue.copy(alpha = 0.2f))
                        .border(0.6.dp, TvBlue, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "Buka Detail & Chart",
                        color = TvBlueSoft,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = TvBlueSoft,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}
