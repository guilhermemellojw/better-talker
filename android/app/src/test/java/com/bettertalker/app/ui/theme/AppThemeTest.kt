package com.bettertalker.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1 — escala tipográfica própria (base Material 3, sem fonte externa). */
class AppThemeTest {

    @Test
    fun escalaDoCorpoConsolidada() {
        assertEquals(26.sp, AppTypography.bodyLarge.lineHeight)
        assertEquals(22.sp, AppTypography.bodyMedium.lineHeight)
    }

    @Test
    fun titulosComPesosEHierarquia() {
        assertEquals(22.sp, AppTypography.titleLarge.fontSize)
        assertEquals(FontWeight.SemiBold, AppTypography.titleLarge.fontWeight)
        assertEquals(16.sp, AppTypography.titleMedium.fontSize)
        assertEquals(FontWeight.SemiBold, AppTypography.titleMedium.fontWeight)
        assertEquals(14.sp, AppTypography.titleSmall.fontSize)
    }

    @Test
    fun semFonteCustomizada() {
        // Base do sistema (SansSerif → Roboto no Android); nada baixado.
        assertEquals(FontFamily.SansSerif, AppTypography.bodyLarge.fontFamily)
        assertEquals(FontFamily.SansSerif, AppTypography.titleLarge.fontFamily)
    }

    @Test
    fun linksPassamContrasteAA() {
        // P1: cores explícitas dos links vs. surface dos dois temas.
        assertTrue(
            "light: ${contrastRatio(LinkBlueLight, Color(0xFFFFFDF5))}",
            contrastRatio(LinkBlueLight, Color(0xFFFFFDF5)) >= 4.5
        )
        assertTrue(
            "dark: ${contrastRatio(LinkBlueDark, Color(0xFF1E1C15))}",
            contrastRatio(LinkBlueDark, Color(0xFF1E1C15)) >= 4.5
        )
    }
}
