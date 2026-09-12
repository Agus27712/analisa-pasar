package agu.analys.ui.components.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.MarketTick
import agu.analys.ui.theme.*

@Composable
fun WatchlistManagerSettings(
    currentWatchlist: Set<String>,
    dashboardTicks: Map<String, MarketTick>,
    onAddPair: (String) -> Unit,
    onRemovePair: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val popularSuggestions = listOf(
        "BTCIDR", "ETHIDR", "SOLIDR", "DOGEIDR", "XRPIDR", "PEPEIDR",
        "SUIIDR", "ADAIDR", "BNBIDR", "SHIBIDR", "NEARIDR", "AVAXIDR",
        "RENDERIDR", "FETIDR", "TRXIDR", "LINKIDR", "FLOKIIDR", "BONKIDR"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. WATCHLIST SUMMARY & PRESETS
        SectionHeader("PAKET PRESET WATCHLIST CEPAT")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total Koin Dipantau:",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )
                    Box(
                        modifier = Modifier
                            .background(TvBlue.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${currentWatchlist.size} Pair Aktif",
                            color = TvBlue,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "Pilih paket preset instan:",
                    color = TvTextSecondary,
                    fontSize = 10.5.sp
                )
                Spacer(Modifier.height(8.dp))

                val presets = listOf(
                    Triple("top10", "👑 Top 10 Kripto", Color(0xFFFFB300)),
                    Triple("scalp", "⚡ Scalping Gems", TvGreen),
                    Triple("ai", "🤖 AI & Web3", Color(0xFFAB47BC)),
                    Triple("layer1", "🧱 Layer-1 Top", TvBlue),
                    Triple("default", "🔄 Reset Default (BTC)", TvTextSecondary)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { (presetKey, label, color) ->
                        OutlinedButton(
                            onClick = {
                                onApplyPreset(presetKey)
                                Toast.makeText(context, "Preset $label diterapkan!", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
                            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 2. TAMBAH PAIR MANUAL / CARI KOIN
        SectionHeader("TAMBAH PAIR MANUAL / CARI KOIN")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it.uppercase() },
                        placeholder = { Text("Ketik simbol (contoh: SOL, PEPE, SUI)", fontSize = 11.sp, color = TvTextSecondary) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, null, tint = TvBlue, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, null, tint = TvTextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvBlue,
                            unfocusedBorderColor = TvBorder,
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary,
                            cursorColor = TvBlue
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    )

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val clean = searchQuery.trim().replace("/", "").replace("_", "")
                            if (clean.isNotBlank()) {
                                val symbolWithQuote = if (clean.endsWith("IDR", true) || clean.endsWith("USDT", true)) {
                                    clean
                                } else {
                                    "${clean}IDR"
                                }
                                onAddPair(symbolWithQuote)
                                searchQuery = ""
                                Toast.makeText(context, "$symbolWithQuote ditambahkan ke Watchlist", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = searchQuery.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Tambah", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Koin Populer Siap Tambah:", color = TvTextSecondary, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))

                // Popular suggestions chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    popularSuggestions.forEach { sym ->
                        val isTracked = currentWatchlist.any { it.equals(sym, ignoreCase = true) }
                        val base = sym.replace("IDR", "").replace("USDT", "")
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isTracked) TvGreen.copy(alpha = 0.15f) else TvSurface)
                                .border(
                                    1.dp,
                                    if (isTracked) TvGreen.copy(alpha = 0.5f) else TvBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    if (isTracked) {
                                        onRemovePair(sym)
                                        Toast.makeText(context, "$sym dihapus dari Watchlist", Toast.LENGTH_SHORT).show()
                                    } else {
                                        onAddPair(sym)
                                        Toast.makeText(context, "$sym ditambahkan ke Watchlist", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = base,
                                    color = if (isTracked) TvGreen else TvTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (isTracked) Icons.Default.Check else Icons.Default.Add,
                                    contentDescription = null,
                                    tint = if (isTracked) TvGreen else TvTextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 3. DAFTAR KOIN WATCHLIST SAAT INI
        SectionHeader("DAFTAR PAIR WATCHLIST AKTIF (${currentWatchlist.size})")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (currentWatchlist.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Watchlist kosong. Tambahkan pair di atas.", color = TvTextSecondary, fontSize = 11.5.sp)
                    }
                } else {
                    currentWatchlist.toList().forEachIndexed { index, symbol ->
                        val base = symbol.replace("IDR", "").replace("USDT", "").uppercase()
                        val tick = dashboardTicks[symbol] ?: dashboardTicks[symbol.lowercase()] ?: dashboardTicks[base]
                        val priceStr = tick?.let { "Rp %,d".format(it.price.toLong()) } ?: "-"
                        val change24h = tick?.change24h ?: 0.0
                        val isUp = change24h >= 0

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TvSurface)
                                .border(1.dp, TvBorder.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(TvBlue.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = base.take(3),
                                        color = TvBlue,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "$base/IDR",
                                        color = TvTextPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = priceStr,
                                        color = TvTextSecondary,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (tick != null) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                (if (isUp) TvGreen else TvRed).copy(alpha = 0.15f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "%s%.2f%%".format(if (isUp) "+" else "", change24h),
                                            color = if (isUp) TvGreen else TvRed,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }

                                IconButton(
                                    onClick = {
                                        onRemovePair(symbol)
                                        Toast.makeText(context, "$symbol dihapus", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Hapus",
                                        tint = TvRed.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
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
