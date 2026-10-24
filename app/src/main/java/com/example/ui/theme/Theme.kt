package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val MonolithDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color(0xFF061E0F),
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = OnEmeraldContainer,
    secondary = SplashGold,
    onSecondary = Color(0xFF261E00),
    tertiary = Color(0xFF58A6FF),
    background = MonolithBg,
    onBackground = TextPrimary,
    surface = MonolithSurface,
    onSurface = TextPrimary,
    surfaceVariant = MonolithSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = MonolithBorder,
    error = DangerRed
)

@Composable
fun MyApplicationTheme(
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context).copy(
            background = MonolithBg,
            surface = MonolithSurface,
            surfaceVariant = MonolithSurfaceElevated
        )
    } else {
        MonolithDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
