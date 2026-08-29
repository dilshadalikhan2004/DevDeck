package com.devdeck.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devdeck.app.R

val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val HankenGroteskFont = GoogleFont("Hanken Grotesk")
val HankenGroteskFamily = FontFamily(
    Font(googleFont = HankenGroteskFont, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = HankenGroteskFont, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = HankenGroteskFont, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = HankenGroteskFont, fontProvider = provider, weight = FontWeight.Bold)
)

val JetBrainsMonoFont = GoogleFont("JetBrains Mono")
val JetBrainsMonoFamily = FontFamily(
    Font(googleFont = JetBrainsMonoFont, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = JetBrainsMonoFont, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = JetBrainsMonoFont, fontProvider = provider, weight = FontWeight.SemiBold)
)

val LuminaLightColors = lightColorScheme(
    primary = Color(0xFF0059b5), // Electric Blue
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0071e3),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF006e28), // Forest Mint
    onSecondary = Color.White,
    background = Color(0xFFF5F5F7), // Apple-style Off-White
    onBackground = Color(0xFF1B1B1D),
    surface = Color.White,
    onSurface = Color(0xFF1B1B1D),
    surfaceVariant = Color(0xFFE4E2E4),
    onSurfaceVariant = Color(0xFF414753),
    outline = Color(0xFF717785),
    error = Color(0xFFBA1A1A)
)

val LuminaTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = (-0.02).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).sp
    ),
    bodyLarge = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.06.sp
    )
)

@Composable
fun LuminaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // For now, only supporting Light Mode as per Lumina Executive spec
    val colorScheme = LuminaLightColors
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = LuminaTypography,
        content = content
    )
}

// Custom extensions for Lumina design system
object LuminaDesign {
    val GlassBg = Color(0xB3FFFFFF) // 70% White
    val HairlineStroke = Color(0x1A000000) // 10% Black for hairlines on solid
    val GlassBorder = Color(0x4DFFFFFF) // 30% White for hairlines on glass
}
