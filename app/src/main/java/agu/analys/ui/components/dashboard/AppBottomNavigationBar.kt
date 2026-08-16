package agu.analys.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextSecondary

enum class NavTab {
    WATCHLIST, SIMULASI, BELAJAR, SETTINGS
}

@Composable
fun AppBottomNavigationBar(
    currentTab: NavTab,
    onSelectTab: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF09101A))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(
            icon = Icons.Default.FormatListBulleted,
            label = "Watchlist",
            isSelected = currentTab == NavTab.WATCHLIST,
            onClick = { onSelectTab(NavTab.WATCHLIST) }
        )
        NavItem(
            icon = Icons.AutoMirrored.Filled.CompareArrows,
            label = "Simulasi",
            isSelected = currentTab == NavTab.SIMULASI,
            onClick = { onSelectTab(NavTab.SIMULASI) }
        )
        NavItem(
            icon = Icons.Default.MenuBook,
            label = "Belajar",
            isSelected = currentTab == NavTab.BELAJAR,
            onClick = { onSelectTab(NavTab.BELAJAR) }
        )
        NavItem(
            icon = Icons.Default.Settings,
            label = "Settings",
            isSelected = currentTab == NavTab.SETTINGS,
            onClick = { onSelectTab(NavTab.SETTINGS) }
        )
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) TvGreen else TvTextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = if (isSelected) TvGreen else TvTextSecondary,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
