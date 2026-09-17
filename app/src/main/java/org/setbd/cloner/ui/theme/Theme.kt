package org.setbd.cloner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.setbd.cloner.data.ThemeMode

/**
 * Material You theme. On Android 12+ the system dynamic palette is used
 * (Material You behavior); older devices fall back to the brand palette
 * from the kept launcher mark.
 */
private val DarkScheme = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimaryDark,
    secondary = BrandSecondary,
    tertiary = BrandTertiary,
    background = BrandNavy,
    surface = BrandSurfaceDark,
    surfaceVariant = BrandSurfaceVariantDark
)

private val LightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    secondary = LightSecondary,
    tertiary = LightTertiary,
    background = LightBackground,
    surface = LightSurface
)

@Composable
fun SetbdClonerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkScheme else LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = ClonerTypography,
        content = content
    )
}
