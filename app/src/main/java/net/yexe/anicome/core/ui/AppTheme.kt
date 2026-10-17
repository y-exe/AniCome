package net.yexe.anicome

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable fun AniComeTheme(mode: String = "dark", content: @Composable () -> Unit) {
    val languageContext = LocalContext.current
    val selection = (languageContext.applicationContext as? AniComeApp)?.runtime?.language?.collectAsStateWithLifecycle()?.value ?: "system"
    val language = resolveUiLanguage(selection, androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language)
    remember(languageContext) { UiTranslations.initialize(languageContext); true }
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(
        primary = Color.White, onPrimary = Color.Black, primaryContainer = Color(0xFF383838), onPrimaryContainer = Color.White, inversePrimary = Color.Black,
        secondary = Color(0xFFE4E4E4), onSecondary = Color.Black, secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color.White,
        tertiary = Color(0xFFD0D0D0), onTertiary = Color.Black, tertiaryContainer = Color(0xFF333333), onTertiaryContainer = Color.White,
        background = Color(0xFF101010), onBackground = Color(0xFFF2F2F2), surface = Color(0xFF101010), onSurface = Color(0xFFF2F2F2), surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFBDBDBD), surfaceTint = Color.White,
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF181818), surfaceContainer = Color(0xFF1D1D1D), surfaceContainerHigh = Color(0xFF272727), surfaceContainerHighest = Color(0xFF363636), surfaceDim = Color(0xFF101010), surfaceBright = Color(0xFF363636),
        outline = Color(0xFF777777), outlineVariant = Color(0xFF3B3B3B), inverseSurface = Color(0xFFEAEAEA), inverseOnSurface = Color(0xFF151515),
        error = Color(0xFFE0E0E0), onError = Color.Black, errorContainer = Color(0xFF383838), onErrorContainer = Color.White
    ) else lightColorScheme(
        primary = Color.Black, onPrimary = Color.White, primaryContainer = Color(0xFFDCDCDC), onPrimaryContainer = Color.Black, inversePrimary = Color.White,
        secondary = Color(0xFF343434), onSecondary = Color.White, secondaryContainer = Color(0xFFE3E3E3), onSecondaryContainer = Color.Black,
        tertiary = Color(0xFF444444), onTertiary = Color.White, tertiaryContainer = Color(0xFFE1E1E1), onTertiaryContainer = Color.Black,
        background = Color(0xFFFAFAFA), onBackground = Color(0xFF111111), surface = Color(0xFFFAFAFA), onSurface = Color(0xFF111111), surfaceVariant = Color(0xFFE8E8E8), onSurfaceVariant = Color(0xFF626262), surfaceTint = Color.Black,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F4F4), surfaceContainer = Color(0xFFF0F0F0), surfaceContainerHigh = Color(0xFFE8E8E8), surfaceContainerHighest = Color(0xFFDCDCDC), surfaceDim = Color(0xFFDEDEDE), surfaceBright = Color.White,
        outline = Color(0xFF888888), outlineVariant = Color(0xFFD1D1D1), inverseSurface = Color(0xFF242424), inverseOnSurface = Color.White,
        error = Color(0xFF333333), onError = Color.White, errorContainer = Color(0xFFE1E1E1), onErrorContainer = Color.Black
    )
    val monochrome = colors.copy(
        primaryFixed = Color(0xFFE5E5E5), primaryFixedDim = Color(0xFFC8C8C8), onPrimaryFixed = Color(0xFF151515), onPrimaryFixedVariant = Color(0xFF414141),
        secondaryFixed = Color(0xFFE3E3E3), secondaryFixedDim = Color(0xFFC7C7C7), onSecondaryFixed = Color(0xFF171717), onSecondaryFixedVariant = Color(0xFF424242),
        tertiaryFixed = Color(0xFFE1E1E1), tertiaryFixedDim = Color(0xFFC5C5C5), onTertiaryFixed = Color(0xFF191919), onTertiaryFixedVariant = Color(0xFF444444))
    val family = referenceFontFamily()
    val base = TextStyle(fontFamily = family, fontSynthesis = FontSynthesis.All, fontWeight = FontWeight.Normal)
    val typography = Typography(
        displayLarge = base.copy(fontSize = 57.sp, lineHeight = 64.sp, fontWeight = FontWeight.SemiBold),
        displayMedium = base.copy(fontSize = 45.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold),
        displaySmall = base.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
        titleLarge = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold), titleMedium = base.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold), titleSmall = base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 24.sp), bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 22.sp), bodySmall = base.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), labelMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp), labelSmall = base.copy(fontSize = 14.sp, lineHeight = 20.sp))
    MaterialTheme(colorScheme = monochrome, motionScheme = MotionScheme.expressive(), typography = typography, shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp))) {
        CompositionLocalProvider(LocalUiLanguage provides language, androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr, content = content)
    }
}
