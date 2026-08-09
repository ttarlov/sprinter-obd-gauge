package com.revel.obdgauge.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors =
    darkColorScheme(
        primary = DarkPrimary,
        onPrimary = DarkOnPrimary,
        secondary = DarkSecondary,
        background = DarkBackground,
        surface = DarkSurface,
    )

private val LightColors =
    lightColorScheme(
        primary = LightPrimary,
        onPrimary = LightOnPrimary,
        secondary = LightSecondary,
        background = LightBackground,
        surface = LightSurface,
    )

/**
 * App-wide Material 3 theme. Defaults to dark unconditionally — this app runs on a dash
 * mount, usually at night. A user-facing light-mode toggle can be added in settings
 * (Sprint 1, OBD-21) if wanted; [forceDark] exists in the meantime for screenshot tests.
 */
@Composable
fun ObdGaugeTheme(
    forceDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (forceDark) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ObdGaugeTypography,
        content = content,
    )
}
