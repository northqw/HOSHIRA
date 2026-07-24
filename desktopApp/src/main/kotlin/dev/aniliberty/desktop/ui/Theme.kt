package dev.aniliberty.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.aniliberty.desktop.desktopapp.generated.resources.Res
import dev.aniliberty.desktop.desktopapp.generated.resources.inter_variable
import dev.aniliberty.desktop.desktopapp.generated.resources.montserrat_variable
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
    val inter = FontFamily(
        Font(Res.font.inter_variable, FontWeight.Normal),
        Font(Res.font.inter_variable, FontWeight.Medium),
        Font(Res.font.inter_variable, FontWeight.SemiBold),
        Font(Res.font.inter_variable, FontWeight.Bold),
    )
    val montserrat = FontFamily(
        Font(Res.font.montserrat_variable, FontWeight.Medium),
        Font(Res.font.montserrat_variable, FontWeight.SemiBold),
        Font(Res.font.montserrat_variable, FontWeight.Bold),
    )

    return Typography(
        displayLarge = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.Bold,
            fontSize = 52.sp,
            lineHeight = 60.sp,
            letterSpacing = (-1.05).sp,
        ),
        headlineLarge = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = (-0.45).sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.SemiBold,
            fontSize = 23.sp,
            lineHeight = 31.sp,
            letterSpacing = (-0.2).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = montserrat,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 27.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 23.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 27.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = inter,
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
        content = content,
    )
}
