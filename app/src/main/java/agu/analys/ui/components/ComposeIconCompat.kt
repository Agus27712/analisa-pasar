package agu.analys.ui.components

import androidx.compose.material3.Icon as Material3Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Compatibility wrapper for AISignalCard's Icon usage.
 * Keeps the component package API explicit without changing the existing card code.
 */
@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Material3Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
    )
}
