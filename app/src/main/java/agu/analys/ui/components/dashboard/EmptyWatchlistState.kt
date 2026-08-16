package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

@Composable
fun EmptyWatchlistState(onAddClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Star, null, tint = DashboardColors.Gold, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("Watchlist masih kosong", color = TvTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tambahkan koin favorit atau pilih dari Volume Tertinggi di atas.",
                color = TvTextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onAddClick,
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = Color.Black)
                Spacer(Modifier.width(6.dp))
                Text("Tambah Koin", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}
