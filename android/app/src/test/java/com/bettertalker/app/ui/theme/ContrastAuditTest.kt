package com.bettertalker.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 (P2 estético) — auditoria de contraste WCAG AA dos pares visuais.
 * Limite AA para texto normal: 4.5:1. Pura (sem Android).
 */
class ContrastAuditTest {

    private val aa = 4.5

    private fun assertAa(name: String, fg: Color, bg: Color) {
        val r = contrastRatio(fg, bg)
        assertTrue("$name: ${"%.2f".format(r)} < $aa", r >= aa)
    }

    /** Fundo efetivo de um overlay com alpha sobre outro fundo. */
    private fun blend(fg: Color, bg: Color, alpha: Float): Color = Color(
        red = fg.red * alpha + bg.red * (1f - alpha),
        green = fg.green * alpha + bg.green * (1f - alpha),
        blue = fg.blue * alpha + bg.blue * (1f - alpha),
        alpha = 1f,
    )

    @Test
    fun lightScheme_paresDeTextoPassamAa() {
        val s = LightColors
        assertAa("onSurface/surface", s.onSurface, s.surface)
        assertAa("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
        assertAa("secondary/surface (badges)", s.secondary, s.surface)
        assertAa("tertiary/surface (links)", s.tertiary, s.surface)
        assertAa("error/surface (avisos)", s.error, s.surface)
        assertAa("onPrimary/primary (botões)", s.onPrimary, s.primary)
        assertAa(
            "onPrimaryContainer/primaryContainer (avatar)",
            s.onPrimaryContainer, s.primaryContainer
        )
    }

    @Test
    fun lightScheme_citacaoECodigoPassamAa() {
        val s = LightColors
        assertAa("citação", s.onSurfaceVariant, blend(s.surfaceVariant, s.surface, 0.35f))
        assertAa("código inline", s.onSurface, s.surfaceVariant)
    }

    @Test
    fun darkScheme_paresDeTextoPassamAa() {
        val s = DarkColors
        assertAa("onSurface/surface", s.onSurface, s.surface)
        assertAa("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
        assertAa("secondary/surface (badges)", s.secondary, s.surface)
        assertAa("tertiary/surface (links)", s.tertiary, s.surface)
        assertAa("error/surface (avisos)", s.error, s.surface)
        assertAa("onPrimary/primary (botões)", s.onPrimary, s.primary)
        assertAa(
            "onPrimaryContainer/primaryContainer (avatar)",
            s.onPrimaryContainer, s.primaryContainer
        )
    }

    @Test
    fun darkScheme_citacaoECodigoPassamAa() {
        val s = DarkColors
        assertAa("citação", s.onSurfaceVariant, blend(s.surfaceVariant, s.surface, 0.35f))
        assertAa("código inline", s.onSurface, s.surfaceVariant)
    }

    @Test
    fun amareloPrimarioNaoEhCorDeTextoNoTemaClaro() {
        // Regra do P2: o amarelo `primary` é FUNDO (com onPrimary por cima);
        // como texto sobre surface clara dá ~1.55:1 — nunca usar assim.
        val r = contrastRatio(LightColors.primary, LightColors.surface)
        assertTrue("primário como texto: ${"%.2f".format(r)}", r < aa)
    }
}
