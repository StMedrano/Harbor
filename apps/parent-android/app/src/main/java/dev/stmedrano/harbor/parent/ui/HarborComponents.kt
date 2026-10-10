package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HarborBrand() {
    val brand = MaterialTheme.colorScheme.primary
    val onBrand = MaterialTheme.colorScheme.onPrimary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Canvas(Modifier.size(30.dp)) {
            val unit = size.width / 32f
            drawRoundRect(brand, cornerRadius = CornerRadius(8f * unit))
            val house = Path().apply {
                moveTo(8f * unit, 15f * unit); lineTo(16f * unit, 8f * unit)
                lineTo(24f * unit, 15f * unit); lineTo(24f * unit, 24f * unit)
                lineTo(8f * unit, 24f * unit); close()
            }
            drawPath(house, onBrand, style = Stroke(2.4f * unit, join = StrokeJoin.Round))
            drawCircle(Color(0xFFE9A23B), 2.6f * unit, Offset(16f * unit, 18f * unit))
        }
        Text("Harbor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

data class HarborTab(val label: String, val selected: Boolean, val onClick: () -> Unit)

/** Segmented tab bar modelled on the web `.seg` control: tinted track, raised selected pill. */
@Composable
fun HarborTabs(tabs: List<HarborTab>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, shape).padding(3.dp)) {
        tabs.forEach { tab ->
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp))
                .background(if (tab.selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                .selectable(selected = tab.selected, role = Role.Tab, onClick = tab.onClick)
                .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(tab.label, style = MaterialTheme.typography.labelLarge,
                    color = if (tab.selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun HarborAvatar(name: String, size: Dp = 30.dp) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
fun HarborButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = MaterialTheme.shapes.medium, content = content)

@Composable
fun HarborOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    OutlinedButton(onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = MaterialTheme.shapes.medium, content = content)

@Composable
fun HarborPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}
