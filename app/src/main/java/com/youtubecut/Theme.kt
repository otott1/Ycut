package com.youtubecut

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// أزرق مخضرّ هادئ للنص والأزرار، وكهرماني للأوقات والمقاطع المقتطعة على الشريط الزمني.
private val Light = lightColorScheme(
    primary = Color(0xFF1D5C63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E8EA),
    onPrimaryContainer = Color(0xFF0B2F33),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE7C0),
    onSecondaryContainer = Color(0xFF2E1D00),
    background = Color(0xFFF5F6F3),
    onBackground = Color(0xFF17232B),
    surface = Color.White,
    onSurface = Color(0xFF17232B),
    surfaceVariant = Color(0xFFE6EAE8),
    onSurfaceVariant = Color(0xFF4A5A5E),
    outline = Color(0xFF8A9A9C),
    outlineVariant = Color(0xFFCBD4D3),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6CC4CB),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF144A50),
    onPrimaryContainer = Color(0xFFD3E8EA),
    secondary = Color(0xFFE8B75A),
    onSecondary = Color(0xFF442B00),
    secondaryContainer = Color(0xFF5E3F00),
    onSecondaryContainer = Color(0xFFFBE7C0),
    background = Color(0xFF0F1A1D),
    onBackground = Color(0xFFE4ECEC),
    surface = Color(0xFF172428),
    onSurface = Color(0xFFE4ECEC),
    surfaceVariant = Color(0xFF26363A),
    onSurfaceVariant = Color(0xFFB4C4C6),
    outline = Color(0xFF7D8E90),
    outlineVariant = Color(0xFF3A4A4D),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun YoutubeCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
