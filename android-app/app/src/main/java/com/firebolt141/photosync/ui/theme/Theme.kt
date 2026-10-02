package com.firebolt141.ubertrag.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Same palette as the Windows tool: warm ivory + clay (light), near-black slate (dark).
private val Clay        = Color(0xFFD97757)
private val ClayDeep    = Color(0xFFB85C3E)
private val Ivory       = Color(0xFFFAF9F5)
private val IvoryDeep   = Color(0xFFF0EEE6)
private val Slate       = Color(0xFF141413)
private val SlateSoft   = Color(0xFF5E5D59)
private val Line        = Color(0xFFE3DACC)
private val Olive       = Color(0xFF788C5D)
private val Sky         = Color(0xFF6A9BCC)

private val LightColors = lightColorScheme(
    primary              = ClayDeep,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFF6DED3),
    onPrimaryContainer   = Color(0xFF5A2410),
    secondary            = Olive,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFE6EBDD),
    onSecondaryContainer = Color(0xFF2B3520),
    tertiary             = Sky,
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFDCE8F4),
    onTertiaryContainer  = Color(0xFF1C3550),
    error                = Color(0xFFB3261E),
    errorContainer       = Color(0xFFF9DEDC),
    onErrorContainer     = Color(0xFF410E0B),
    background           = Ivory,
    onBackground         = Slate,
    surface              = Ivory,
    onSurface            = Slate,
    surfaceVariant       = IvoryDeep,
    onSurfaceVariant     = SlateSoft,
    surfaceContainerLowest  = Color.White,
    surfaceContainerLow     = Color(0xFFFFFFFF),
    surfaceContainer        = Color(0xFFF5F3EC),
    surfaceContainerHigh    = Color(0xFFF0EEE6),
    surfaceContainerHighest = Color(0xFFE8E5DB),
    outline              = Color(0xFFB8B0A2),
    outlineVariant       = Line,
)

private val DarkColors = darkColorScheme(
    primary              = Clay,
    onPrimary            = Color(0xFF2A0F05),
    primaryContainer     = Color(0xFF5A2D1D),
    onPrimaryContainer   = Color(0xFFFFDBCD),
    secondary            = Color(0xFFA9BC8E),
    onSecondary          = Color(0xFF1C2612),
    secondaryContainer   = Color(0xFF2F3A24),
    onSecondaryContainer = Color(0xFFDDE8CC),
    tertiary             = Color(0xFF9CC3EA),
    onTertiary           = Color(0xFF0D2338),
    tertiaryContainer    = Color(0xFF233A52),
    onTertiaryContainer  = Color(0xFFD3E5F7),
    error                = Color(0xFFF2B8B5),
    errorContainer       = Color(0xFF5C1D19),
    onErrorContainer     = Color(0xFFF9DEDC),
    background           = Color(0xFF151515),
    onBackground         = Color(0xFFEDEAE3),
    surface              = Color(0xFF151515),
    onSurface            = Color(0xFFEDEAE3),
    surfaceVariant       = Color(0xFF242322),
    onSurfaceVariant     = Color(0xFFB4B0A8),
    surfaceContainerLowest  = Color(0xFF0F0F0F),
    surfaceContainerLow     = Color(0xFF1B1B1A),
    surfaceContainer        = Color(0xFF1F1F1E),
    surfaceContainerHigh    = Color(0xFF262625),
    surfaceContainerHighest = Color(0xFF302F2D),
    outline              = Color(0xFF6F6B64),
    outlineVariant       = Color(0xFF34332F),
)

/** Serif headings (like the Windows tool), clean sans-serif body text. */
private val AppTypography = Typography().run {
    copy(
        headlineLarge  = headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        headlineSmall  = headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        titleLarge     = titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun UbertragTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography  = AppTypography,
        content     = content,
    )
}
