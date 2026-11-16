package com.qwenpaint.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0E0F13)
private val Panel = Color(0xFF17191F)
private val PanelHigh = Color(0xFF20232B)
private val Violet = Color(0xFF7C6CFF)
private val Mint = Color(0xFF39D2C0)
private val TextMain = Color(0xFFECEDF2)
private val TextDim = Color(0xFF9AA0AE)
private val Danger = Color(0xFFFF6B6B)

private val AppColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2550),
    onPrimaryContainer = Color(0xFFD9D3FF),
    secondary = Mint,
    onSecondary = Color(0xFF04201D),
    background = Ink,
    onBackground = TextMain,
    surface = Panel,
    onSurface = TextMain,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = TextDim,
    outline = Color(0xFF333845),
    error = Danger,
    onError = Color.White,
)

@Composable
fun QwenPaintTheme(content: @Composable () -> Unit) {
    // 生图工具固定深色：预览图像色彩判断更准
    MaterialTheme(
        colorScheme = AppColors,
        typography = Typography(),
        content = content,
    )
}
