package quarkflix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object Palette {
    val Background = Color(0xFF0A0A0D)
    val Surface = Color(0xFF17171C)
    val Text = Color(0xFFF2F2F2)
    val TextSecondary = Color(0xB3FFFFFF)
    val TextTertiary = Color(0x80FFFFFF)
    val Accent = Color(0xFFF5C04A)
    val Focus = Color.White
}

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Text),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.Text),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = Palette.TextSecondary),
    bodySmall = TextStyle(fontSize = 12.sp, color = Palette.TextSecondary),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.TextSecondary),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary),
)

@Composable
fun QuarkflixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            background = Palette.Background,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            onBackground = Palette.Text,
        ),
        typography = typography,
        content = content,
    )
}

/** "#rrggbb" → Color, 解析失败用默认背景色. */
fun parseColor(hex: String?, fallback: Color = Palette.Surface): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
