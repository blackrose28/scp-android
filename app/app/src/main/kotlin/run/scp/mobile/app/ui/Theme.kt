package run.scp.mobile.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** App palette. The background is the terminal's, so the terminal and the other screens look like one app. */
internal object ScpColors {
    const val BACKGROUND = TERMINAL_BG
    const val SURFACE = 0xFF1B1B1D.toInt()
    const val SURFACE_VARIANT = 0xFF262629.toInt()
    const val PRIMARY = 0xFF7FB2E5.toInt()
    const val ON_PRIMARY = 0xFF0A2A47.toInt()
}

private val scpDarkColors = darkColorScheme(
    primary = Color(ScpColors.PRIMARY),
    onPrimary = Color(ScpColors.ON_PRIMARY),
    background = Color(ScpColors.BACKGROUND),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(ScpColors.SURFACE),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(ScpColors.SURFACE_VARIANT),
    onSurfaceVariant = Color(0xFFB8B8BC),
    outline = Color(0xFF5A5A60),
)

/** Dark theme that also paints the background and sets the content colour (a bare MaterialTheme does neither). */
@Composable
fun ScpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scpDarkColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
