package dev.tabletcomics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Night = Color(0xFF090B11)
private val Surface = Color(0xFF111520)
private val Raised = Color(0xFF191E2A)
private val TextPrimary = Color(0xFFF2F0F7)
private val TextSecondary = Color(0xFFA5A8B5)

fun accentFor(name: String): Color = when (name) {
    "teal" -> Color(0xFF69DCCB)
    "amber" -> Color(0xFFFFC857)
    else -> Color(0xFFD7B8FF)
}

@Composable
fun ComicsTheme(accentName: String, content: @Composable () -> Unit) {
    val accent = accentFor(accentName)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF211533),
            secondary = accent,
            tertiary = accent,
            background = Night,
            onBackground = TextPrimary,
            surface = Surface,
            onSurface = TextPrimary,
            surfaceVariant = Raised,
            onSurfaceVariant = TextSecondary,
            outline = Color(0xFF343949),
            errorContainer = Color(0xFF7E211B),
            onErrorContainer = Color(0xFFFFE8E5),
        ),
        content = content,
    )
}
