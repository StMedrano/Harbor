package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Composable
fun HarborTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFCFE5DC), onPrimary = Color(0xFF0F1514), secondary = Color(0xFF6CC2A8),
        background = Color(0xFF0F1514), surface = Color(0xFF171F1E),
        surfaceVariant = Color(0xFF1F2927), outline = Color(0xFF2B3633), outlineVariant = Color(0xFF2B3633),
        surfaceContainerHigh = Color(0xFF171F1E), surfaceContainerHighest = Color(0xFF1F2927),
        onBackground = Color(0xFFECEBE5), onSurface = Color(0xFFECEBE5),
        onSurfaceVariant = Color(0xFFAAB3AE), error = Color(0xFFF0796C),
        // Web --accs / --warn / --dans tints, so no stock Material purple shows through.
        primaryContainer = Color(0xFF1D3A33), onPrimaryContainer = Color(0xFFCFE5DC),
        secondaryContainer = Color(0xFF1D3A33), onSecondaryContainer = Color(0xFFECEBE5),
        tertiary = Color(0xFFE9A23B), onTertiary = Color(0xFF0F1514),
        tertiaryContainer = Color(0xFF3A2C14), onTertiaryContainer = Color(0xFFF2C37A),
        errorContainer = Color(0xFF3C1F1B), onErrorContainer = Color(0xFFF0796C),
        surfaceContainerLowest = Color(0xFF171F1E), surfaceContainerLow = Color(0xFF171F1E), surfaceContainer = Color(0xFF1F2927),
        surfaceTint = Color(0xFFCFE5DC),
    ) else lightColorScheme(
        primary = Color(0xFF163A3A), onPrimary = Color(0xFFF4EFE6), secondary = Color(0xFF1F6B5C),
        background = Color(0xFFECE6DA), surface = Color(0xFFFBF8F2),
        surfaceVariant = Color(0xFFF3EEE4), outline = Color(0xFFE2DACB), outlineVariant = Color(0xFFE2DACB),
        surfaceContainerHigh = Color(0xFFFBF8F2), surfaceContainerHighest = Color(0xFFF3EEE4),
        onBackground = Color(0xFF1C2422), onSurface = Color(0xFF1C2422),
        onSurfaceVariant = Color(0xFF56605C), error = Color(0xFFB4382C),
        primaryContainer = Color(0xFFDCEBE4), onPrimaryContainer = Color(0xFF163A3A),
        secondaryContainer = Color(0xFFDCEBE4), onSecondaryContainer = Color(0xFF1C2422),
        tertiary = Color(0xFFE9A23B), onTertiary = Color(0xFF1C2422),
        tertiaryContainer = Color(0xFFFBECD2), onTertiaryContainer = Color(0xFF7A4D08),
        errorContainer = Color(0xFFF6DCD7), onErrorContainer = Color(0xFFB4382C),
        surfaceContainerLowest = Color(0xFFFBF8F2), surfaceContainerLow = Color(0xFFFBF8F2), surfaceContainer = Color(0xFFF3EEE4),
        surfaceTint = Color(0xFF163A3A),
    )
    MaterialTheme(colorScheme = colors, typography = harborTypography(), shapes = Shapes(
        small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(22.dp),
    ), content = content)
}

// Sizes, weights and tracking follow web/harbor-family/css/styles.css (h1 26/600, h2 16/600, body 15, labels 13/600).
private fun harborTypography(): Typography {
    val base = Typography()
    return base.copy(
        headlineMedium = base.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
        headlineSmall = base.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelMedium = base.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    )
}
