package pl.flipfocus.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import pl.flipfocus.resources.*

/** Design tokens from Figma Uvw2EdJMOEZ5XV7JagGpmd, frames 2:442 / 2:478 / 2:512 / 2:1055. */
object FocusColors {
    val background = Color(0xFF0B0B0C)
    val surface = Color(0xFF141416)
    val raised = Color(0xFF1B1B1E)
    val border = Color(0xFF29292D)
    val text = Color(0xFFFAFAFA)
    // Lifted from Figma #71717A to meet 4.5:1 for small, meaningful labels.
    val muted = Color(0xFFA1A1AA)
    val green = Color(0xFF10B981)
    val onGreen = Color(0xFF061711)
    val greenSurface = Color(0xFF08261D)
    val lime = Color(0xFF84CC16)
    val amber = Color(0xFFF59E0B)
    val amberSurface = Color(0xFF2A1C09)
    val gold = Color(0xFFF5C451)
    val silver = Color(0xFFB9C0CA)
    val bronze = Color(0xFFC98A54)
    // Bottom nav glass card, figma node 10:3.
    val navSurface = Color(0xFF141416).copy(alpha = 0.78f)
    val navBorder = Color(0xFF303333).copy(alpha = 0.72f)
}

object FocusSpace {
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val page = 24.dp
    val section = 32.dp
    val touch = 48.dp
    val timer = 286.dp
}

@Composable
fun FocusTheme(content: @Composable () -> Unit) {
    val inter = FontFamily(
        Font(Res.font.inter_light, FontWeight.Light),
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
    )
    fun style(size: Int, weight: FontWeight = FontWeight.Normal, lineHeight: Int = size + 6) =
        TextStyle(fontFamily = inter, fontSize = size.sp, fontWeight = weight, lineHeight = lineHeight.sp)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FocusColors.green, onPrimary = FocusColors.onGreen,
            background = FocusColors.background, onBackground = FocusColors.text,
            surface = FocusColors.surface, onSurface = FocusColors.text,
            surfaceVariant = FocusColors.raised, onSurfaceVariant = FocusColors.muted,
            outline = FocusColors.border, secondary = FocusColors.lime,
            error = FocusColors.amber,
        ),
        typography = Typography(
            displayLarge = style(58, FontWeight.Light, 70),
            headlineLarge = style(30, FontWeight.Bold, 35),
            headlineMedium = style(22, FontWeight.Bold, 28),
            headlineSmall = style(20, FontWeight.Normal, 26),
            titleLarge = style(18, FontWeight.SemiBold, 24),
            titleMedium = style(15, FontWeight.Bold, 22),
            bodyLarge = style(15, lineHeight = 22),
            bodyMedium = style(13, lineHeight = 19),
            bodySmall = style(11, lineHeight = 16),
            labelLarge = style(15, FontWeight.Bold, 21),
            labelMedium = style(13, FontWeight.Medium, 18),
            labelSmall = style(11, FontWeight.Bold, 16),
        ),
        content = {
            CompositionLocalProvider(LocalContentColor provides FocusColors.text, LocalTextStyle provides style(15)) {
                content()
            }
        },
    )
}
