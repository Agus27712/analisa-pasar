package agu.analys.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary

@Composable
fun DashboardHeader(
    connectionLabel: String,
    connectionColor: Color,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onAddAsset: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(9.dp).background(connectionColor, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(
                connectionLabel,
                color = connectionColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.45.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Refresh, "Refresh", tint = TvTextPrimary, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Settings, "Settings", tint = TvTextPrimary, modifier = Modifier.size(24.dp))
            }
            Button(
                onClick = onAddAsset,
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("add_asset_button")
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = Color.Black)
                Spacer(Modifier.width(4.dp))
                Text("Tambah Koin", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Watchlist Koin", color = TvTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    }
}
