package pl.flipfocus.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import pl.flipfocus.resources.*

@Composable
fun AssetIcon(asset: DrawableResource, modifier: Modifier = Modifier, description: String? = null) {
    Image(painterResource(asset), description, modifier)
}

@Composable
fun TimerRing(
    remainingMillis: Long,
    fraction: Float,
    color: Color,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.widthIn(max = FocusSpace.timer).fillMaxWidth().aspectRatio(1f)
            .semantics(mergeDescendants = true) { contentDescription = countdown(remainingMillis) + (label?.let { " $it" } ?: "") },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawArc(FocusColors.border, -90f, 360f, false, androidx.compose.ui.geometry.Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 0.994f), false,
                androidx.compose.ui.geometry.Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(countdown(remainingMillis), style = MaterialTheme.typography.displayLarge,
                color = FocusColors.text, modifier = Modifier.clearAndSetSemantics { })
            if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = color,
                modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

@Composable
fun DurationSelector(selectedMinutes: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.widthIn(max = FocusSpace.timer).fillMaxWidth().clip(CircleShape)
        .background(FocusColors.surface).padding(FocusSpace.tiny).selectableGroup()) {
        listOf(5, 10, 15, 25, 45).forEach { minutes ->
            val selected = selectedMinutes == minutes
            Box(Modifier.weight(1f).heightIn(min = FocusSpace.touch).clip(CircleShape)
                .background(if (selected) FocusColors.raised else Color.Transparent)
                .clickable(role = Role.RadioButton) { onSelect(minutes) }
                .semantics { this.selected = selected }
                .padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                Text("${minutes}m",
                    color = if (selected) FocusColors.text else FocusColors.muted,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
fun FocusButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 58.dp), enabled = enabled,
        shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(16.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun FocusCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(FocusColors.surface)
        .border(1.dp, FocusColors.border, RoundedCornerShape(24.dp)).padding(20.dp), content = content)
}

@Composable
fun StreakBadge(days: Int, copy: Copy, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = FocusColors.lime) {
    Box(modifier.heightIn(min = FocusSpace.touch).clip(CircleShape).clickable(onClick = onClick)
        .semantics { contentDescription = copy.history + ", " + copy.days(days) }, contentAlignment = Alignment.Center) {
        Text("🔥 ${copy.days(days)}", color = color, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clip(CircleShape).background(FocusColors.surface)
                .border(1.dp, FocusColors.border, CircleShape).padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
