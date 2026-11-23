package com.doppelisk.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One palette, lifted from the app the phone shipped with.
 *
 * The manager is a tool that runs over a root shell and shows hexadecimal
 * identifiers all day; a light theme would be a second set of colours to keep
 * coherent for no benefit.
 */
object Palette {
    val Ink = Color(0xFF0B0C0F)
    val Surface = Color(0xFF121317)
    val SurfaceElevated = Color(0xFF17191E)
    val SurfaceHigh = Color(0xFF1F2127)
    val SurfaceHighest = Color(0xFF282B33)

    val Periwinkle = Color(0xFFA8C7FA)
    val PeriwinkleStrong = Color(0xFF7CA6F4)
    val PeriwinkleDim = Color(0xFF1C2A44)

    val Mint = Color(0xFF7ED7A6)
    val MintDim = Color(0xFF153023)

    val Amber = Color(0xFFE6C27A)
    val AmberDim = Color(0xFF352A15)

    val Rose = Color(0xFFF28B82)
    val RoseDim = Color(0xFF3A1D1B)

    val TextPrimary = Color(0xFFE7E9EE)
    val TextSecondary = Color(0xFFA2A7B3)
    val TextTertiary = Color(0xFF707581)

    val Outline = Color(0xFF343841)
    val OutlineSoft = Color(0xFF22252C)
}

private val DoppeliskColors = darkColorScheme(
    primary = Palette.Periwinkle,
    onPrimary = Color(0xFF10203A),
    primaryContainer = Palette.PeriwinkleDim,
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Palette.Mint,
    onSecondary = Color(0xFF082316),
    secondaryContainer = Palette.MintDim,
    onSecondaryContainer = Color(0xFFC8F5DA),
    tertiary = Palette.Amber,
    onTertiary = Color(0xFF2E240C),
    tertiaryContainer = Palette.AmberDim,
    onTertiaryContainer = Color(0xFFFFE7B8),
    background = Palette.Ink,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    error = Palette.Rose,
    onError = Palette.RoseDim,
    errorContainer = Palette.RoseDim,
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Palette.Outline,
    outlineVariant = Palette.OutlineSoft,
    surfaceTint = Palette.Periwinkle,
)

/**
 * The app's weight tweaks on top of the Material defaults.
 *
 * Material already draws every one of these sizes at the same metrics; only the
 * headings are a step heavier than stock, which is what gives the screens their
 * deliberately "tool" look.
 */
private val DoppeliskTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
    )
}

/** 6 / 8 / 10 / 12 / 16 from extra small to extra large. */
private val DoppeliskShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun DoppeliskTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DoppeliskColors,
        typography = DoppeliskTypography,
        shapes = DoppeliskShapes,
        content = content,
    )
}
