package app.hoshira.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.hoshira.desktop.desktopapp.generated.resources.Res
import app.hoshira.desktop.desktopapp.generated.resources.montserrat_variable
import org.jetbrains.compose.resources.Font

object AniColors {
    val Background = Color(0xFF090A0C)
    val BackgroundSoft = Color(0xFF101114)
    val Surface = Color(0xFF17181C)
    val SurfaceHigh = Color(0xFF23252A)
    val SurfaceHighest = Color(0xFF303238)
    val Border = Color(0xFF303238)
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

@Composable
private fun aniTypography(): Typography {
    val montserrat = FontFamily(
        Font(Res.font.montserrat_variable, FontWeight.Medium),
        Font(Res.font.montserrat_variable, FontWeight.SemiBold),
        Font(Res.font.montserrat_variable, FontWeight.Bold),
        Font(Res.font.montserrat_variable, FontWeight.ExtraBold),
    )
    val defaults = Typography()

    return Typography(
        displayLarge = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 52.sp,
            lineHeight = 60.sp,
            letterSpacing = (-1.05).sp,
        ),
        displayMedium = defaults.displayMedium.copy(
            fontFamily = montserrat,
            fontWeight = FontWeight.ExtraBold,
        ),
        displaySmall = defaults.displaySmall.copy(
            fontFamily = montserrat,
            fontWeight = FontWeight.ExtraBold,
        ),
        headlineLarge = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = (-0.45).sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.Bold,
            fontSize = 23.sp,
            lineHeight = 31.sp,
            letterSpacing = (-0.2).sp,
        ),
        headlineSmall = defaults.headlineSmall.copy(
            fontFamily = montserrat,
            fontWeight = FontWeight.Bold,
        ),
        titleLarge = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 27.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 23.sp,
        ),
        titleSmall = defaults.titleSmall.copy(
            fontFamily = montserrat,
            fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 27.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        ),
        bodySmall = defaults.bodySmall.copy(
            fontFamily = montserrat,
            fontWeight = FontWeight.Medium,
        ),
        labelLarge = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
    )
}

@Composable
fun HoshiraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AniColorScheme,
        typography = aniTypography(),
    ) {
        ProvideTextStyle(
            value = MaterialTheme.typography.bodyMedium,
            content = content,
        )
    }
}
