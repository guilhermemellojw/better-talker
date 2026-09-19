package com.bettertalker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Paleta Samsung clássica: amarelo como destaque primário.
val SamsungYellow = Color(0xFFFBD44A)
val SamsungYellowDeep = Color(0xFFF6C90E)
val SamsungYellowSoft = Color(0xFFFCE9A8)
val SamsungInk = Color(0xFF1B1B1B)

private val LightColors = lightColorScheme(
    primary = SamsungYellowDeep,
    onPrimary = SamsungInk,
    primaryContainer = SamsungYellowSoft,
    onPrimaryContainer = SamsungInk,
    secondary = Color(0xFF5B6B7B),
    secondaryContainer = Color(0xFFE8EDF3),
    tertiary = Color(0xFF1A6FEB),
    surface = Color(0xFFFFFDF5),
    surfaceVariant = Color(0xFFFFF3C4),
    background = Color(0xFFFFFDF5),
    outline = Color(0xFFE3D9B8),
    errorContainer = Color(0xFFFDE8E8)
)
private val DarkColors = darkColorScheme(
    primary = SamsungYellow,
    onPrimary = SamsungInk,
    primaryContainer = Color(0xFF3A2F10),
    onPrimaryContainer = SamsungYellowSoft,
    secondary = Color(0xFF9AA7B8),
    tertiary = Color(0xFF8AB4F8),
    surface = Color(0xFF1E1C15),
    surfaceVariant = Color(0xFF2A2618),
    background = Color(0xFF141310),
    outline = Color(0xFF4A4230),
    errorContainer = Color(0xFF4A1F1F)
)

val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

// Cores de nota/pasta estilo Samsung Notes
val FolderYellow = SamsungYellow
val FolderBlue = Color(0xFF7FB3F7)
val FolderGreen = Color(0xFF8BD1A8)
val FolderPink = Color(0xFFF2A3C0)
val FolderGray = Color(0xFFC9D2DC)

val NOTE_COLORS = listOf(FolderYellow, FolderBlue, FolderGreen, FolderPink, FolderGray)

enum class ThemeMode { AUTO, LIGHT, DARK }

@Composable
fun BetterTalkerTheme(mode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
