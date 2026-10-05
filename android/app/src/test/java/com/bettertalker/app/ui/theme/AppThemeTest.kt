package com.bettertalker.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
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
}
