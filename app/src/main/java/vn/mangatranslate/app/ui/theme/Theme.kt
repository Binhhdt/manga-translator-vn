package vn.mangatranslate.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF8A3D)
val BoxBlue = Color(0xFF3DB8FF)
val BoxRed = Color(0xFFFF4D4D)
val BoxGreen = Color(0xFF7CFC9A)
val BoxOrange = Color(0xFFFFA726)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A0E05),
    primaryContainer = Color(0xFF4A2A12),
    onPrimaryContainer = Color(0xFFFFDCC6),
    secondary = BoxBlue,
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Color(0xFFB8B8BE),
    error = Color(0xFFFF6B6B)
)

@Composable
fun MangaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
