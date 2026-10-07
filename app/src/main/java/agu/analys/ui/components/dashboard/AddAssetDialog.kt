package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.model.TradingPair
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvBlue
import agu.analys.ui.theme.TvCyan
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

@Composable
fun AddAssetDialog(
    currentFavorites: Set<String> = emptySet(),
    marketDataSource: agu.analys.config.MarketDataSource = agu.analys.config.MarketDataSource.TOKOCRYPTO,
    onDismiss: () -> Unit,
    onAddPair: (TradingPair) -> Unit
) {
    val isToko = marketDataSource == agu.analys.config.MarketDataSource.TOKOCRYPTO
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember(marketDataSource) { mutableStateOf(if (isToko) "USDT" else "IDR") }
    val tabs = if (isToko) listOf("USDT", "IDR", "SEMUA") else listOf("IDR", "USDT", "SEMUA")

    // Ambil daftar pair dari dynamic symbol repository Tokocrypto / Indodax
    val availablePairs = remember(searchQuery, selectedTab, marketDataSource) {
        val quoteFilter = if (selectedTab == "SEMUA") null else selectedTab
        val results = if (searchQuery.isNotBlank()) {
            val dynamicMatches = TokocryptoSymbolRepository.searchSymbols(searchQuery, quoteFilter)
            val indodaxMatches = TradingPair.POPULAR_INDODAX_PAIRS.filter { 
                (it.baseAsset.contains(searchQuery, ignoreCase = true) || it.symbol.contains(searchQuery, ignoreCase = true)) &&
                (quoteFilter == null || it.quoteAsset.equals(quoteFilter, ignoreCase = true))
            }
            if (isToko) (dynamicMatches + indodaxMatches).distinctBy { it.symbol }
            else (indodaxMatches + dynamicMatches).distinctBy { it.symbol }
        } else {
            if (isToko) {
                TokocryptoSymbolRepository.getAllTradingPairs(quoteFilter)
            } else {
                TradingPair.popularPairsForSource(marketDataSource).filter { quoteFilter == null || it.quoteAsset.equals(quoteFilter, ignoreCase = true) }
            }
        }
        if (results.isNotEmpty()) results else TradingPair.popularPairsForSource(marketDataSource)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DashboardColors.Surface),
            border = BorderStroke(1.dp, DashboardColors.Border)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = TvCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "Tambah Pasar ${marketDataSource.label}",
                                color = TvTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                "Ditemukan ${availablePairs.size} pair aktif",
                                color = TvTextSecondary,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = TvTextSecondary)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Search input
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Cari simbol pasar (cth: BTC, SOL, PEPE, ETH...)", color = TvTextSecondary, fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TvCyan, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TvTextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_asset_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvCyan,
                        unfocusedBorderColor = DashboardColors.Border,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary,
                        cursorColor = TvCyan
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(10.dp))

                // Quote Tabs (IDR / USDT / SEMUA)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(tabs) { tab ->
                        val isSelected = selectedTab == tab
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) TvCyan else DashboardColors.Card,
                            border = BorderStroke(1.dp, if (isSelected) TvCyan else DashboardColors.Border),
                            modifier = Modifier.clickable { selectedTab = tab }
                        ) {
                            Text(
                                text = tab,
                                color = if (isSelected) Color.Black else TvTextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // List of pairs
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availablePairs, key = { it.symbol }) { pair ->
                        val isAdded = currentFavorites.contains(pair.symbol)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddPair(pair) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
                            border = BorderStroke(1.dp, if (isAdded) TvCyan.copy(alpha = 0.5f) else DashboardColors.Border)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssetBadge(pair.baseAsset, if (isAdded) TvCyan else TvGreen)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            pair.baseAsset,
                                            color = TvTextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "/${pair.quoteAsset}",
                                            color = TvCyan,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(pair.displayName, color = TvTextSecondary, fontSize = 10.sp)
                                }
                                if (isAdded) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = null,
                                            tint = TvAmber,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            "Favorit",
                                            color = TvAmber,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = { onAddPair(pair) },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvCyan),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(
                                            "+ Tambah",
                                            color = Color.Black,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
