package app.browservault.vault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF071018),
    primaryContainer = Color(0xFF143243),
    onPrimaryContainer = Color(0xFFB8F2FA),
    secondary = Color(0xFF1B2740),
    onSecondary = Color(0xFFE6EDF7),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF121A2B),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1B2740),
    onSurfaceVariant = Color(0xFF8BA0BD),
    outline = Color(0xFF33425F),
    error = Color(0xFFF87171)
)

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
