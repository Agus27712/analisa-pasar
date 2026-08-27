package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.util.Locale

@Composable
fun SellTpSlSection(
    isRealMode: Boolean,
    isAutoSellActive: Boolean,
    onAutoSellActiveChanged: (Boolean) -> Unit,
    tp1Price: String,
    onTp1PriceChanged: (String) -> Unit,
    tp1Percent: String,
    onTp1PercentChanged: (String) -> Unit,
    tp2Price: String,
    onTp2PriceChanged: (String) -> Unit,
    tp2Percent: String,
    onTp2PercentChanged: (String) -> Unit,
    stopLossPrice: String,
    onStopLossPriceChanged: (String) -> Unit,
    quoteAsset: String,
    onSaveParams: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isAutoSellActive) TvGreen.copy(alpha = 0.1f) else TvSurfaceVariant,
                RoundedCornerShape(10.dp)
            )
            .border(
                1.dp,
                if (isAutoSellActive) TvGreen.copy(alpha = 0.5f) else TvBorder,
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isRealMode) "🎯 AUTO TP1 & TP2 SERVER" else "🎯 AUTO TP1, TP2 & STOP LOSS",
                    color = if (isAutoSellActive) TvGreen else Color(0xFF90A4AE),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Black
                )

                Switch(
                    checked = isAutoSellActive,
                    onCheckedChange = onAutoSellActiveChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TvGreen,
                        checkedTrackColor = TvGreen.copy(alpha = 0.4f),
                        uncheckedThumbColor = Color(0xFF78909C),
                        uncheckedTrackColor = Color(0xFF1E2D3D)
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }

            if (isAutoSellActive) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Row for TP1
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.6f)) {
                            Text("Harga TP 1 ($quoteAsset)", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            OutlinedTextField(
                                value = tp1Price,
                                onValueChange = onTp1PriceChanged,
                                placeholder = { Text("Harga TP 1", fontSize = 10.5.sp, color = TvTextSecondary.copy(alpha = 0.5f)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = TvGreen,
                                    unfocusedBorderColor = TvBorder,
                                    focusedContainerColor = TvSurfaceVariant,
                                    unfocusedContainerColor = TvSurfaceVariant,
                                    focusedTextColor = TvTextPrimary,
                                    unfocusedTextColor = TvTextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TvTextPrimary)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Porsi TP 1 %", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            OutlinedTextField(
                                value = tp1Percent,
                                onValueChange = { input ->
                                    onTp1PercentChanged(input)
                                    val p1 = input.toDoubleOrNull()
                                    if (p1 != null) {
                                        val p2 = (100.0 - p1).coerceIn(0.0, 100.0)
                                        val p2Str = if (p2 % 1.0 == 0.0) p2.toInt().toString() else String.format(Locale.US, "%.1f", p2)
                                        onTp2PercentChanged(p2Str)
                                    }
                                },
                                placeholder = { Text("50", fontSize = 10.5.sp, color = TvTextSecondary.copy(alpha = 0.5f)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = TvGreen,
                                    unfocusedBorderColor = TvBorder,
                                    focusedContainerColor = TvSurfaceVariant,
                                    unfocusedContainerColor = TvSurfaceVariant,
                                    focusedTextColor = TvTextPrimary,
                                    unfocusedTextColor = TvTextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TvTextPrimary)
                            )
                        }
                    }

                    // Row for TP2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.6f)) {
                            Text("Harga TP 2 ($quoteAsset)", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            OutlinedTextField(
                                value = tp2Price,
                                onValueChange = onTp2PriceChanged,
                                placeholder = { Text("Harga TP 2", fontSize = 10.5.sp, color = TvTextSecondary.copy(alpha = 0.5f)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = TvGreen,
                                    unfocusedBorderColor = TvBorder,
                                    focusedContainerColor = TvSurfaceVariant,
                                    unfocusedContainerColor = TvSurfaceVariant,
                                    focusedTextColor = TvTextPrimary,
                                    unfocusedTextColor = TvTextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TvTextPrimary)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Porsi TP 2 %", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            OutlinedTextField(
                                value = tp2Percent,
                                onValueChange = { input ->
                                    onTp2PercentChanged(input)
                                    val p2 = input.toDoubleOrNull()
                                    if (p2 != null) {
                                        val p1 = (100.0 - p2).coerceIn(0.0, 100.0)
                                        val p1Str = if (p1 % 1.0 == 0.0) p1.toInt().toString() else String.format(Locale.US, "%.1f", p1)
                                        onTp1PercentChanged(p1Str)
                                    }
                                },
                                placeholder = { Text("50", fontSize = 10.5.sp, color = TvTextSecondary.copy(alpha = 0.5f)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = TvGreen,
                                    unfocusedBorderColor = TvBorder,
                                    focusedContainerColor = TvSurfaceVariant,
                                    unfocusedContainerColor = TvSurfaceVariant,
                                    focusedTextColor = TvTextPrimary,
                                    unfocusedTextColor = TvTextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TvTextPrimary)
                            )
                        }
                    }

                    // Stop Loss or Real Mode Notice
                    if (!isRealMode) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val slColor = if (LocalAppColors.current == LightAppColors) Color(0xFFD32F2F) else Color(0xFFEF9A9A)
                            Text("Harga Stop Loss ($quoteAsset)", color = slColor, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            OutlinedTextField(
                                value = stopLossPrice,
                                onValueChange = onStopLossPriceChanged,
                                placeholder = { Text("Harga Stop Loss", fontSize = 10.5.sp, color = slColor.copy(alpha = 0.5f)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = TvRed,
                                    unfocusedBorderColor = TvBorder,
                                    focusedContainerColor = TvSurfaceVariant,
                                    unfocusedContainerColor = TvSurfaceVariant,
                                    focusedTextColor = TvTextPrimary,
                                    unfocusedTextColor = TvTextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TvTextPrimary)
                            )
                        }
                    } else {
                        // Di Real Mode, SL otomatis ditiadakan karena keterbatasan Indodax Order Type (Market order ga ada SL di API spot biasa seringnya)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .border(1.dp, TvAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(text = "⚠️", fontSize = 12.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Di Akun Riil, Stop Loss (SL) otomatis ditiadakan karena keterbatasan saldo terkunci di server Indodax. Gunakan TP1 & TP2 murni server agar aman.",
                                    color = TvAmber,
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onSaveParams,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TvGreen, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Simpan Target Jual Otomatis", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            } else {
                Text(
                    text = "Aktifkan untuk setting target profit & stop loss otomatis via server (tetap jalan walau HP mati).",
                    color = TvTextSecondary,
                    fontSize = 9.5.sp
                )
            }
        }
    }
}
