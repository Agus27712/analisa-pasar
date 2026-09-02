package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import agu.analys.model.TradingPair
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

@Composable
fun AddAssetDialog(
    currentFavorites: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onAddPair: (TradingPair) -> Unit
) {
    val popularPairs = TradingPair.POPULAR_PAIRS
    var manualInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DashboardColors.Surface),
            border = BorderStroke(1.dp, DashboardColors.Border)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = TvAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Tambah Koin ke Favorit",
                            color = TvTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = TvTextSecondary)
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text(
                    "Masukkan simbol pair manual (IDR/USDT):",
                    color = TvTextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        placeholder = { Text("cth: DOGEIDR, SOLIDR, SOLUSDT", color = TvTextSecondary, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_asset_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvAmber,
                            unfocusedBorderColor = DashboardColors.Border,
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary,
                            cursorColor = TvAmber
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        onClick = {
                            val trimmed = manualInput.trim().uppercase()
                            if (trimmed.isNotEmpty()) {
                                onAddPair(TradingPair.fromCustomSymbol(trimmed))
                                manualInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvAmber),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("manual_add_button")
                    ) {
                        Text("+ Favorit", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Atau pilih dari daftar populer Indodax:",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(popularPairs, key = { it.symbol }) { pair ->
                        val isAdded = currentFavorites.contains(pair.symbol)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddPair(pair) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
                            border = BorderStroke(1.dp, if (isAdded) TvAmber.copy(alpha = 0.6f) else DashboardColors.Border)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssetBadge(pair.baseAsset, if (isAdded) TvAmber else TvGreen)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        pair.displayName,
                                        color = TvTextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(pair.symbol, color = TvTextSecondary, fontSize = 10.sp)
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
                                        colors = ButtonDefaults.buttonColors(containerColor = TvAmber),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(
                                            "+ Favorit",
                                            color = Color.Black,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
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
