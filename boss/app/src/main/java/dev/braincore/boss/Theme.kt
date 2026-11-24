package dev.braincore.boss

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Boss's own palette instead of the wallpaper's: navy from the launcher icon for the app, a warm amber for
// "this waits on you", cool greys for everything else.

val LightColors = lightColorScheme(
    primary = Color(0xFF2B4C7E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7F6),
    onPrimaryContainer = Color(0xFF0F2340),
    secondary = Color(0xFF55657A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E9F0),
    onSecondaryContainer = Color(0xFF1C2735),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEFD9),
    onTertiaryContainer = Color(0xFF6B2E05),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFDE8E6),
    onErrorContainer = Color(0xFF6E1A13),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE9ECF1),
    onSurfaceVariant = Color(0xFF5B6472),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF1F5),
    surfaceContainerHighest = Color(0xFFE6EAF0),
    outline = Color(0xFFC9CFD8),
    outlineVariant = Color(0xFFE5E8ED),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C4EE),
    onPrimary = Color(0xFF0F2340),
    primaryContainer = Color(0xFF253D63),
    onPrimaryContainer = Color(0xFFDDE7F6),
    secondary = Color(0xFFB7C2D1),
    onSecondary = Color(0xFF1C2735),
    secondaryContainer = Color(0xFF2A3445),
    onSecondaryContainer = Color(0xFFDCE3EC),
    tertiary = Color(0xFFFBBF6A),
    onTertiary = Color(0xFF3F1D02),
    tertiaryContainer = Color(0xFF4A2C10),
    onTertiaryContainer = Color(0xFFFFE3C2),
    error = Color(0xFFF7A39B),
    onError = Color(0xFF5A130D),
    errorContainer = Color(0xFF4D1915),
    onErrorContainer = Color(0xFFFDE8E6),
    background = Color(0xFF0D1320),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF0D1320),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF222B3B),
    onSurfaceVariant = Color(0xFF9AA4B2),
    surfaceContainerLowest = Color(0xFF0A0F1A),
    surfaceContainerLow = Color(0xFF151C2A),
    surfaceContainer = Color(0xFF172030),
    surfaceContainerHigh = Color(0xFF1F2839),
    surfaceContainerHighest = Color(0xFF283245),
    outline = Color(0xFF3A4457),
    outlineVariant = Color(0xFF263042),
)

private val base = Typography()

val BossType = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

/** Small caps-style section label ("FOR YOU"). */
val Overline = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)

val BossShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun BossTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = BossType, shapes = BossShapes, content = content)
