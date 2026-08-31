package agu.analys.ui.components.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.MarketDataSource
import agu.analys.ui.theme.*

@Composable
fun DetailBottomActions(
    marketDataSource: MarketDataSource,
    onOpenPortfolio: () -> Unit,
    onOpenExchange: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onOpenPortfolio,
            modifier = Modifier.weight(1f).height(42.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TvBlue),
            border = BorderStroke(1.dp, TvBorder)
        ) {
            Icon(Icons.Default.AccountBalanceWallet, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("Portofolio", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = onOpenExchange,
            modifier = Modifier.weight(1f).height(42.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TvBlue)
        ) {
            Text("Buka ${marketDataSource.label}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}
