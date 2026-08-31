package agu.analys.ui.components.detail.alert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.PriceAlertType
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun CreateAlertTabContent(
    symbol: String,
    currentPrice: Double,
    quoteAsset: String,
    selectedType: PriceAlertType,
    onTypeChange: (PriceAlertType) -> Unit,
    targetPriceInput: String,
    onTargetPriceChange: (String) -> Unit,
    noteInput: String,
    onNoteChange: (String) -> Unit,
    focusManager: FocusManager,
    onSaveAlert: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            MarketPriceInfoCard(
                currentPrice = currentPrice,
                quoteAsset = quoteAsset
            )
        }

        item {
            QuickPresetsSection(
                currentPrice = currentPrice,
                onPresetSelected = { type, targetPrice, note ->
                    onTypeChange(type)
                    if (targetPrice != null) {
                        onTargetPriceChange(PriceFormatter.formatIdrNumber(targetPrice))
                    }
                    onNoteChange(note)
                }
            )
        }

        item {
            AlertConditionSelector(
                selectedType = selectedType,
                onTypeSelected = onTypeChange
            )
        }

        if (selectedType == PriceAlertType.PRICE_ABOVE || selectedType == PriceAlertType.PRICE_BELOW) {
            item {
                OutlinedTextField(
                    value = targetPriceInput,
                    onValueChange = onTargetPriceChange,
                    label = { Text("Target Price ($quoteAsset)", fontSize = 11.sp) },
                    placeholder = { Text("Example: 1,450,000,000", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF263C52),
                        focusedContainerColor = Color(0xFF101C2B),
                        unfocusedContainerColor = Color(0xFF101C2B),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            OutlinedTextField(
                value = noteInput,
                onValueChange = onNoteChange,
                label = { Text("Note / Label (Optional)", fontSize = 11.sp) },
                placeholder = { Text("e.g., TP1 resistance level", fontSize = 11.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color(0xFF263C52),
                    focusedContainerColor = Color(0xFF101C2B),
                    unfocusedContainerColor = Color(0xFF101C2B),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Button(
                onClick = onSaveAlert,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save & Activate Alert", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun MarketPriceInfoCard(
    currentPrice: Double,
    quoteAsset: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF102033), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Market Price:", color = TvTextSecondary, fontSize = 10.5.sp)
        Text(
            text = "${PriceFormatter.formatIdrNumber(currentPrice)} $quoteAsset",
            color = Color.White,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun QuickPresetsSection(
    currentPrice: Double,
    onPresetSelected: (PriceAlertType, Double?, String) -> Unit
) {
    Text(
        text = "QUICK 1-TAP PRESETS:",
        color = Color(0xFF90A4AE),
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold
    )

    Spacer(Modifier.height(4.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        QuickPresetButton(
            label = "+3% TP",
            color = TvGreen,
            onClick = {
                onPresetSelected(
                    PriceAlertType.PRICE_ABOVE,
                    currentPrice * 1.03,
                    "Take Profit Target +3%"
                )
            },
            modifier = Modifier.weight(1f)
        )
        QuickPresetButton(
            label = "+5% TP",
            color = TvGreen,
            onClick = {
                onPresetSelected(
                    PriceAlertType.PRICE_ABOVE,
                    currentPrice * 1.05,
                    "Take Profit Target +5%"
                )
            },
            modifier = Modifier.weight(1f)
        )
        QuickPresetButton(
            label = "-2% SL",
            color = TvRed,
            onClick = {
                onPresetSelected(
                    PriceAlertType.PRICE_BELOW,
                    currentPrice * 0.98,
                    "Stop Loss Level -2%"
                )
            },
            modifier = Modifier.weight(1f)
        )
        QuickPresetButton(
            label = "-4% SL",
            color = TvRed,
            onClick = {
                onPresetSelected(
                    PriceAlertType.PRICE_BELOW,
                    currentPrice * 0.96,
                    "Stop Loss Level -4%"
                )
            },
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(Modifier.height(4.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        QuickPresetButton(
            label = "RSI < 30 (Oversold)",
            color = Color(0xFF00E5FF),
            onClick = {
                onPresetSelected(
                    PriceAlertType.RSI_OVERSOLD,
                    null,
                    "RSI Oversold Dip Buyer"
                )
            },
            modifier = Modifier.weight(1f)
        )
        QuickPresetButton(
            label = "Second-Wave Reclaim",
            color = Color(0xFFFFD54F),
            onClick = {
                onPresetSelected(
                    PriceAlertType.SECOND_WAVE_RECLAIM,
                    null,
                    "Second-Wave Reclaim Trigger"
                )
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun AlertConditionSelector(
    selectedType: PriceAlertType,
    onTypeSelected: (PriceAlertType) -> Unit
) {
    Text(
        text = "ALERT CONDITION:",
        color = Color(0xFF90A4AE),
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold
    )

    Spacer(Modifier.height(4.dp))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PriceAlertType.entries.forEach { type ->
            val isSelected = selectedType == type
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) Color(0xFF162D47) else Color(0xFF101B29),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (isSelected) Color(0xFF00E5FF) else Color(0xFF1B2E42),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onTypeSelected(type) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { onTypeSelected(type) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = Color(0xFF00E5FF),
                        unselectedColor = Color(0xFF546E7A)
                    ),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = type.label,
                        color = if (isSelected) Color.White else TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = type.description,
                        color = Color(0xFF78909C),
                        fontSize = 9.5.sp
                    )
                }
            }
        }
    }
}

@Composable
fun QuickPresetButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
