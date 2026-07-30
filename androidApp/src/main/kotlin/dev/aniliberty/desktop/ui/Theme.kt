package dev.aniliberty.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.aniliberty.android.R

object AniColors {
    val Background = Color.Black
    val BackgroundSoft = Color(0xFF050505)
    val Surface = Color(0xFF101010)
    val SurfaceHigh = Color(0xFF1A1A1A)
    val SurfaceHighest = Color(0xFF262626)
    val Border = Color(0xFF2B2B2B)
    val Red = Color(0xFFE50914)
    val RedBright = Color(0xFFFF3340)
    val Orange = Color(0xFFFF4D00)
    val OrangeBright = Color(0xFFFF6A00)
    val Amber = Color(0xFFFFB000)
    val Text = Color(0xFFF7F7F7)
    val TextMuted = Color(0xFFAAAEB6)
    val Success = Color(0xFF4ADE80)
}

private val AniColorScheme = darkColorScheme(
    primary = AniColors.Orange,
    onPrimary = Color.White,
    secondary = AniColors.OrangeBright,
    background = AniColors.Background,
    onBackground = AniColors.Text,
    surface = AniColors.Surface,
    onSurface = AniColors.Text,
    surfaceVariant = AniColors.SurfaceHigh,
    onSurfaceVariant = AniColors.TextMuted,
    outline = AniColors.Border,
    error = Color(0xFFFF6B6B),
)

private val Montserrat = FontFamily(
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
    Font(R.font.montserrat_extrabold, FontWeight.ExtraBold),
    Font(R.font.montserrat_black, FontWeight.Black),
)

private val AndroidTypography = Typography().let { defaults ->
    Typography(
        displayLarge = TextStyle(
            fontFamily = Montserrat,
            fontWeight = FontWeight.Black,
            fontSize = 46.sp,
            lineHeight = 52.sp,
            letterSpacing = (-1.0).sp,
        ),
        displayMedium = defaults.displayMedium.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.7).sp,
        ),
        displaySmall = defaults.displaySmall.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.45).sp,
        ),
        headlineLarge = TextStyle(
            fontFamily = Montserrat,
            fontWeight = FontWeight.Black,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            letterSpacing = (-0.55).sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = Montserrat,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 23.sp,
            lineHeight = 29.sp,
            letterSpacing = (-0.3).sp,
        ),
        headlineSmall = defaults.headlineSmall.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.2).sp,
        ),
        titleLarge = defaults.titleLarge.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.ExtraBold,
        ),
        titleMedium = defaults.titleMedium.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.Bold,
        ),
        titleSmall = defaults.titleSmall.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.Bold,
        ),
        bodyLarge = defaults.bodyLarge.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.Medium,
        ),
        bodyMedium = defaults.bodyMedium.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.Medium,
        ),
        bodySmall = defaults.bodySmall.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.Medium,
        ),
        labelLarge = defaults.labelLarge.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.Bold,
        ),
        labelMedium = defaults.labelMedium.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.Bold,
        ),
        labelSmall = defaults.labelSmall.copy(
            fontFamily = Montserrat,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

@Composable
fun HoshiraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AniColorScheme,
        typography = AndroidTypography,
    ) {
        ProvideTextStyle(
            value = MaterialTheme.typography.bodyMedium,
            content = content,
        )
    }
}
