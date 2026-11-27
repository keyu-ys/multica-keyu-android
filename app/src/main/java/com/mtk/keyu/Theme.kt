package com.mtk.keyu

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 极客单色克制色系：墨黑 + 纯白 + 冷中性灰，仅用 Logo 淡紫 #B2BEE2 / 深靛 #1B2550 作极轻点缀。 */
object Brand {
    val Ink = Color(0xFF0C0D10)
    val InkSoft = Color(0xFF2E323B)
    val Lavender = Color(0xFFB2BEE2)
    val LavenderSoft = Color(0xFFF1F3FA)
    val Navy = Color(0xFF1B2550)
    val Muted = Color(0xFF7B818E)
    val Subtle = Color(0xFFA0A6B3)
    val Surface = Color(0xFFF5F6F8)
    val SurfaceAlt = Color(0xFFF8F9FB)
    val Border = Color(0xFFE8EAEF)
    val Success = Color(0xFF1E8E5A)
    val SuccessSoft = Color(0xFFEDF7F2)
    val Danger = Color(0xFFD63B30)
}

@Composable
fun BrandTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(
        primary = Brand.Ink,
        onPrimary = Color.White,
        secondary = Brand.Navy,
        secondaryContainer = Brand.LavenderSoft,
        onSecondaryContainer = Brand.Navy,
        surface = Color.White,
        background = Color.White,
        surfaceVariant = Brand.Surface,
        outline = Brand.Border,
        error = Brand.Danger,
    ),
    content = content
)
