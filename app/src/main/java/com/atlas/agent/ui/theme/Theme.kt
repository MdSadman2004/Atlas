package com.atlas.agent.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Atlas — warm minimal design system.
 *
 * Direction: warm neutrals (sand / ink) with a single clay accent, sage as the quiet
 * second voice, muted rather than saturated, hairline borders instead of shadows and
 * serif display type for the editorial moments. Motion is spring-led (M3-Expressive
 * flavoured): things settle rather than simply ease.
 */

// ---------------------------------------------------------------- palette

val Clay = Color(0xFFE7A473)        // primary — soft terracotta
val ClayDeep = Color(0xFFB4693B)
val Sage = Color(0xFF9FB08E)        // secondary — quiet green
val Honey = Color(0xFFD9B26A)       // tertiary — muted amber
val WarmRed = Color(0xFFE0806F)

// dark side
val Ink0 = Color(0xFF141110)        // background
val Ink1 = Color(0xFF1B1714)        // surface
val Ink2 = Color(0xFF221C18)        // surface variant
val Ink3 = Color(0xFF2B241E)        // highest surface
val Hairline = Color(0xFF392F26)
val WarmWhite = Color(0xFFF4EEE7)
val WarmGray = Color(0xFFB6A899)

// light side
val Cream0 = Color(0xFFFBF7F2)
val Cream1 = Color(0xFFFFFDFA)
val Cream2 = Color(0xFFF2EAE1)
val InkText = Color(0xFF231D18)

private val DarkWarm = darkColorScheme(
    primary = Clay,
    onPrimary = Color(0xFF2A1707),
    primaryContainer = Color(0xFF3A2415),
    onPrimaryContainer = Color(0xFFF6D9C0),
    secondary = Sage,
    onSecondary = Color(0xFF17200F),
    secondaryContainer = Color(0xFF2A3223),
    onSecondaryContainer = Color(0xFFD7E3CB),
    tertiary = Honey,
    onTertiary = Color(0xFF2A1E05),
    background = Ink0,
    onBackground = WarmWhite,
    surface = Ink1,
    onSurface = WarmWhite,
    surfaceVariant = Ink2,
    onSurfaceVariant = WarmGray,
    surfaceContainerHighest = Ink3,
    outline = Hairline,
    outlineVariant = Color(0xFF2A231D),
    error = WarmRed,
    onError = Color(0xFF2A0E09),
)

private val LightWarm = lightColorScheme(
    primary = ClayDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF7E2D0),
    onPrimaryContainer = Color(0xFF43220E),
    secondary = Color(0xFF5F7050),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EBDC),
    onSecondaryContainer = Color(0xFF25301B),
    tertiary = Color(0xFF9A7630),
    onTertiary = Color.White,
    background = Cream0,
    onBackground = InkText,
    surface = Cream1,
    onSurface = InkText,
    surfaceVariant = Cream2,
    onSurfaceVariant = Color(0xFF6C6055),
    surfaceContainerHighest = Color(0xFFEDE3D8),
    outline = Color(0xFFDCCFC1),
    outlineVariant = Color(0xFFEADFD3),
    error = Color(0xFFA8402F),
    onError = Color.White,
)

// ---------------------------------------------------------------- shapes & type

val AtlasShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val Serif = FontFamily.Serif

val AtlasTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Serif, fontWeight = FontWeight.Medium,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Serif, fontWeight = FontWeight.Medium,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.9.sp),
)

// ---------------------------------------------------------------- motion tokens

/** Spring-led motion: things settle, they don't just ease. */
object Motion {
    val settle: SpringSpec<Float> = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    val snappy: SpringSpec<Float> = spring(dampingRatio = 0.90f, stiffness = Spring.StiffnessMedium)
    val gentle: SpringSpec<Float> = spring(dampingRatio = 0.95f, stiffness = Spring.StiffnessLow)
    val easeOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val FAST = 180
    const val MED = 280
    const val SLOW = 420
}

// ---------------------------------------------------------------- legacy aliases
// Screens import these names; they now map onto the warm palette.
val AtlasMuted = WarmGray
val AtlasGreen = Sage
val AtlasRed = WarmRed
val AtlasAmber = Honey
val AtlasBlue = Clay
val AtlasBg = Ink0
val AtlasSurface = Ink1
val AtlasSurfaceVar = Ink2

// ---------------------------------------------------------------- theme

@Composable
fun AtlasTheme(light: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (light) LightWarm else DarkWarm,
        typography = AtlasTypography,
        shapes = AtlasShapes,
        content = content,
    )
}
