package dev.tabletcomics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
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
fun ComicsTheme(accentName: String, darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val accent = if (darkTheme) accentFor(accentName) else when (accentName) {
        "teal" -> Color(0xFF006C60)
        "amber" -> Color(0xFF805500)
        else -> Color(0xFF6841A5)
    }
    MaterialTheme(
        colorScheme = if (!darkTheme) lightColorScheme(
            primary = accent, onPrimary = Color.White,
            secondary = accent, tertiary = accent,
            background = Color(0xFFF7F7FB), onBackground = Color(0xFF191C25),
            surface = Color(0xFFFFFFFF), onSurface = Color(0xFF191C25),
            surfaceVariant = Color(0xFFE8EAF1), onSurfaceVariant = Color(0xFF515565),
            outline = Color(0xFF737789),
        ) else darkColorScheme(
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
        content = {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
        },
    )
}
