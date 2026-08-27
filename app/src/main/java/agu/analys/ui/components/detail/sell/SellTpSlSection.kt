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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
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

    @Composable
    fun defaultFieldColors(borderFocus: Color = TvBlue) = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = borderFocus,
        unfocusedBorderColor = TvBorder,
        focusedContainerColor = TvCardBackground,
        unfocusedContainerColor = TvCardBackground,
        focusedTextColor = TvTextPrimary,
        unfocusedTextColor = TvTextPrimary,
        cursorColor = borderFocus,
        focusedPlaceholderColor = TvTextMuted,
        unfocusedPlaceholderColor = TvTextMuted
    )

    val fieldText = TextStyle(fontSize = 13.sp, color = TvTextPrimary, fontWeight = FontWeight.Medium)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isAutoSellActive) TvBlue.copy(alpha = 0.08f) else TvSurfaceVariant,
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                if (isAutoSellActive) TvBlue.copy(alpha = 0.45f) else TvBorder,
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isRealMode) "AUTO TP1 & TP2 SERVER" else "AUTO TP1, TP2 & STOP LOSS",
                    color = if (isAutoSellActive) TvBlueSoft else TvTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )

                Switch(
                    checked = isAutoSellActive,
                    onCheckedChange = onAutoSellActiveChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = TvBlue,
                        uncheckedThumbColor = TvTextSecondary,
                        uncheckedTrackColor = TvBorder,
                        uncheckedBorderColor = TvBorder
                    )
                )
            }

            if (isAutoSellActive) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompactNumberField(
                            label = "Harga TP 1 ($quoteAsset)",
                            value = tp1Price,
                            onValueChange = onTp1PriceChanged,
                            placeholder = "Harga TP 1",
                            modifier = Modifier.weight(1.6f),
                            colors = defaultFieldColors(),
                            textStyle = fieldText,
                            imeAction = ImeAction.Next
                        )
                        CompactNumberField(
                            label = "Porsi TP 1 %",
                            value = tp1Percent,
                            onValueChange = { input ->
                                onTp1PercentChanged(input)
                                val p1 = input.toDoubleOrNull()
                                if (p1 != null) {
                                    val p2 = (100.0 - p1).coerceIn(0.0, 100.0)
                                    val p2Str = if (p2 % 1.0 == 0.0) p2.toInt().toString()
                                    else String.format(Locale.US, "%.1f", p2)
                                    onTp2PercentChanged(p2Str)
                                }
                            },
                            placeholder = "50",
                            modifier = Modifier.weight(1f),
                            colors = defaultFieldColors(),
                            textStyle = fieldText,
                            imeAction = ImeAction.Next
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompactNumberField(
                            label = "Harga TP 2 ($quoteAsset)",
                            value = tp2Price,
                            onValueChange = onTp2PriceChanged,
                            placeholder = "Harga TP 2",
                            modifier = Modifier.weight(1.6f),
                            colors = defaultFieldColors(),
                            textStyle = fieldText,
                            imeAction = ImeAction.Next
                        )
                        CompactNumberField(
                            label = "Porsi TP 2 %",
                            value = tp2Percent,
                            onValueChange = { input ->
                                onTp2PercentChanged(input)
                                val p2 = input.toDoubleOrNull()
                                if (p2 != null) {
                                    val p1 = (100.0 - p2).coerceIn(0.0, 100.0)
                                    val p1Str = if (p1 % 1.0 == 0.0) p1.toInt().toString()
                                    else String.format(Locale.US, "%.1f", p1)
                                    onTp1PercentChanged(p1Str)
                                }
                            },
                            placeholder = "50",
                            modifier = Modifier.weight(1f),
                            colors = defaultFieldColors(),
                            textStyle = fieldText,
                            imeAction = ImeAction.Next
                        )
                    }

                    if (!isRealMode) {
                        CompactNumberField(
                            label = "Harga Stop Loss ($quoteAsset)",
                            value = stopLossPrice,
                            onValueChange = onStopLossPriceChanged,
                            placeholder = "Harga Stop Loss",
                            modifier = Modifier.fillMaxWidth(),
                            colors = defaultFieldColors(borderFocus = TvRed),
                            textStyle = fieldText,
                            imeAction = ImeAction.Done,
                            onDone = { focusManager.clearFocus() }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvAmber.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                .border(1.dp, TvAmber.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "Di akun riil, Stop Loss otomatis dimatikan (saldo terkunci di server). Pakai TP1 & TP2 server saja.",
                                color = TvTextPrimary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Button(
                        onClick = onSaveParams,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TvBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Simpan Target Jual Otomatis", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            } else {
                Text(
                    text = "Aktifkan untuk target profit & stop loss otomatis di server (tetap jalan meski HP mati).",
                    color = TvTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun CompactNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    colors: TextFieldColors,
    textStyle: TextStyle,
    imeAction: ImeAction,
    onDone: (() -> Unit)? = null
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = TvTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(placeholder, fontSize = 12.sp, color = TvTextMuted)
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onDone = { onDone?.invoke() }
            ),
            singleLine = true,
            colors = colors,
            textStyle = textStyle,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
