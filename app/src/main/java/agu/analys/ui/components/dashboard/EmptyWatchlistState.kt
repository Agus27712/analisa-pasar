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
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

@Composable
fun EmptyWatchlistState(
    isFavoriteTab: Boolean = false,
    onAddClick: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = if (isFavoriteTab) TvAmber else DashboardColors.Gold,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (isFavoriteTab) "Daftar Favorit Masih Kosong" else "Watchlist Masih Kosong",
                color = TvTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isFavoriteTab) {
                    "Tambahkan koin dengan menekan ikon bintang (⭐) pada kartu koin di Watchlist, atau klik tombol di bawah untuk menambah pair secara manual."
                } else {
                    "Sedang menyinkronkan data koin terpilih dari pasar Indodax."
                },
                color = TvTextSecondary,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )
            if (isFavoriteTab) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAddClick,
                    colors = ButtonDefaults.buttonColors(containerColor = TvAmber),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Tambah Koin Favorit",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
    }
}
