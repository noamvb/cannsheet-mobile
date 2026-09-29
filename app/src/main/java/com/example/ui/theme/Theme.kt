package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.R

internal val LedgerLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF145F58),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9E3DB),
    onPrimaryContainer = Color(0xFF172522),
    secondary = Color(0xFF465B55),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE8E2),
    onSecondaryContainer = Color(0xFF172522),
    tertiary = Color(0xFF53636B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE8EC),
    onTertiaryContainer = Color(0xFF172522),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF4F6F2),
    onBackground = Color(0xFF172522),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF172522),
    surfaceVariant = Color(0xFFCFD8D3),
    onSurfaceVariant = Color(0xFF53625E),
    surfaceTint = Color(0xFF145F58),
    surfaceDim = Color(0xFFD8DEDA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3EF),
    surfaceContainer = Color(0xFFE9EEEA),
    surfaceContainerHigh = Color(0xFFE3E9E5),
    surfaceContainerHighest = Color(0xFFDDE4DF),
    outline = Color(0xFF65736E),
    outlineVariant = Color(0xFFCFD8D3),
    inverseSurface = Color(0xFF29332F),
    inverseOnSurface = Color(0xFFEFF4F1),
    inversePrimary = Color(0xFF9BD2C7),
    scrim = Color(0xFF000000),
)

internal val LedgerDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF83D0C2),
    onPrimary = Color(0xFF12332E),
    primaryContainer = Color(0xFF234A43),
    onPrimaryContainer = Color(0xFFD6F3EC),
    secondary = Color(0xFFB8C9C2),
    onSecondary = Color(0xFF263832),
    secondaryContainer = Color(0xFF394A44),
    onSecondaryContainer = Color(0xFFD5E5DE),
    tertiary = Color(0xFFBCCBD0),
    onTertiary = Color(0xFF29383D),
    tertiaryContainer = Color(0xFF3F5055),
    onTertiaryContainer = Color(0xFFD8E7EB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101816),
    onBackground = Color(0xFFE5EFEB),
    surface = Color(0xFF18221F),
    onSurface = Color(0xFFE5EFEB),
    surfaceVariant = Color(0xFF34443E),
    onSurfaceVariant = Color(0xFFB5C4BE),
    surfaceTint = Color(0xFF83D0C2),
    surfaceDim = Color(0xFF101816),
    surfaceBright = Color(0xFF303A36),
    surfaceContainerLowest = Color(0xFF0B110F),
    surfaceContainerLow = Color(0xFF151E1B),
    surfaceContainer = Color(0xFF19221F),
    surfaceContainerHigh = Color(0xFF232D29),
    surfaceContainerHighest = Color(0xFF2E3834),
    outline = Color(0xFF899992),
    outlineVariant = Color(0xFF34443E),
    inverseSurface = Color(0xFFE1EAE5),
    inverseOnSurface = Color(0xFF27312D),
    inversePrimary = Color(0xFF356D64),
    scrim = Color(0xFF000000),
)

// Static weights, not the variable font: a variable TTF in res/font cannot be
// loaded on API 24-25 (minSdk is 24), and Compose then throws "Could not load font".
internal val PlexSans: FontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight(400)),
    Font(R.font.ibm_plex_sans_medium, FontWeight(500)),
    Font(R.font.ibm_plex_sans_semibold, FontWeight(600)),
)

internal val PlexMono: FontFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight(400)),
    Font(R.font.ibm_plex_mono_medium, FontWeight(500)),
)

internal val LedgerTypography: Typography = Typography().copy(
    displayLarge = Typography().displayLarge.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(400),
        fontSize = 57.sp,
        lineHeight = 64.sp,
    ),
    displayMedium = Typography().displayMedium.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(400),
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = Typography().displaySmall.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(500),
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = Typography().headlineLarge.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(600),
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = Typography().headlineMedium.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(600),
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = Typography().headlineSmall.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(600),
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = Typography().titleLarge.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(600),
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = Typography().titleMedium.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(600),
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = Typography().titleSmall.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(600),
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = Typography().bodyLarge.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(400),
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = Typography().bodyMedium.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(400),
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = Typography().bodySmall.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(400),
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = Typography().labelLarge.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(600),
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = Typography().labelMedium.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(600),
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = Typography().labelSmall.copy(
        fontFamily = PlexSans,
        fontWeight = FontWeight(600),
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

internal val LedgerShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

internal fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
fun CannsheetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) LedgerDarkColors else LedgerLightColors,
        typography = LedgerTypography,
        shapes = LedgerShapes,
        content = content,
    )
}
