package com.doheon.kostolany.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 앱 아이콘(assets/egg_icon.svg)의 색
val Beige = Color(0xFFF3E9D4)
val EggGreen = Color(0xFF1D7A64)   // A: 금리 하락기
val EggNavy = Color(0xFF16233A)    // B: 금리 상승기
val NowOrange = Color(0xFFE0703F)  // 현재 위치
val Ink = Color(0xFF1F1F1F)
val Muted = Color(0xFF6B6B6B)
val CardColor = Color(0xFFFFFAF0)
val UpColor = Color(0xFF2E7D4F)
val DownColor = Color(0xFFC0392B)

fun phaseColor(isA: Boolean) = if (isA) EggGreen else EggNavy

@Composable
fun KostolanyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = EggNavy,
            onPrimary = Beige,
            secondary = EggGreen,
            onSecondary = Beige,
            tertiary = NowOrange,
            background = Beige,
            onBackground = Ink,
            surface = Beige,
            onSurface = Ink,
            surfaceVariant = CardColor,
            onSurfaceVariant = Muted,
            secondaryContainer = Color(0xFFD8E8DF),
            onSecondaryContainer = EggNavy,
        ),
        content = content,
    )
}
