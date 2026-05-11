package com.atlas.virtualspace.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─── Light colour scheme ──────────────────────────────────────────────────────

private val AtlasLightColorScheme = lightColorScheme(
    primary = AtlasPurple,
    onPrimary = AtlasLight,
    primaryContainer = AtlasPurpleLight,
    onPrimaryContainer = AtlasPurpleDark,
    secondary = AtlasBlue,
    onSecondary = AtlasLight,
    secondaryContainer = AtlasBlueLight,
    onSecondaryContainer = AtlasBlueDark,
    tertiary = AtlasAccent,
    onTertiary = AtlasLight,
    tertiaryContainer = AtlasAccentLight,
    onTertiaryContainer = AtlasAccentDark,
    error = AtlasError,
    onError = AtlasLight,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = AtlasLight,
    onBackground = AtlasDark,
    surface = AtlasLightSurface,
    onSurface = AtlasDark,
    surfaceVariant = AtlasLightCard,
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    inverseSurface = AtlasDarkSurface,
    inverseOnSurface = AtlasLight,
    inversePrimary = AtlasPurpleLight,
    surfaceTint = AtlasPurple,
)

// ─── Dark colour scheme ───────────────────────────────────────────────────────

private val AtlasDarkColorScheme = darkColorScheme(
    primary = AtlasPurpleLight,
    onPrimary = AtlasPurpleDark,
    primaryContainer = AtlasPurpleDark,
    onPrimaryContainer = AtlasPurpleLight,
    secondary = AtlasBlueLight,
    onSecondary = AtlasBlueDark,
    secondaryContainer = AtlasBlueDark,
    onSecondaryContainer = AtlasBlueLight,
    tertiary = AtlasAccentLight,
    onTertiary = AtlasAccentDark,
    tertiaryContainer = AtlasAccentDark,
    onTertiaryContainer = AtlasAccentLight,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = AtlasDark,
    onBackground = Color(0xFFE6E1E5),
    surface = AtlasDarkSurface,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = AtlasDarkCard,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = AtlasDark,
    inversePrimary = AtlasPurple,
    surfaceTint = AtlasPurpleLight,
)

// ─── Theme composable ─────────────────────────────────────────────────────────

@Composable
fun AtlasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Dynamic Material You colours on API 33+
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> AtlasDarkColorScheme
        else -> AtlasLightColorScheme
    }

    // Make the status/navigation bars match the theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AtlasTypography,
        content = content,
    )
}
