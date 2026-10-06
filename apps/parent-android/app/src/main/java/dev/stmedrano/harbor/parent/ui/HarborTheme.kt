package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun HarborTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFCFE5DC), secondary = Color(0xFF6CC2A8),
        background = Color(0xFF0F1514), surface = Color(0xFF171F1E),
        surfaceVariant = Color(0xFF1F2927), outline = Color(0xFF2B3633),
        onBackground = Color(0xFFECEBE5), onSurface = Color(0xFFECEBE5),
        onSurfaceVariant = Color(0xFFAAB3AE), error = Color(0xFFF0796C),
    ) else lightColorScheme(
        primary = Color(0xFF163A3A), secondary = Color(0xFF1F6B5C),
        background = Color(0xFFECE6DA), surface = Color(0xFFFBF8F2),
        surfaceVariant = Color(0xFFF3EEE4), outline = Color(0xFFE2DACB),
        onBackground = Color(0xFF1C2422), onSurface = Color(0xFF1C2422),
        onSurfaceVariant = Color(0xFF56605C), error = Color(0xFFB4382C),
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(22.dp),
    ), content = content)
}
