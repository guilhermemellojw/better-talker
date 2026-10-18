package com.bettertalker.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 (P2 estético) — auditoria de contraste WCAG AA de TODAS as paletas
 * ([AppPalettes.all]) × claro/escuro. Limite AA para texto normal: 4.5:1.
 * Pura (sem Android).
 */
class ContrastAuditTest {

    private val aa = 4.5

    private fun assertAa(palette: String, mode: String, name: String, fg: Color, bg: Color) {
        val r = contrastRatio(fg, bg)
        assertTrue("$palette/$mode — $name: ${"%.2f".format(r)} < $aa", r >= aa)
    }

    /** Fundo efetivo de um overlay com alpha sobre outro fundo. */
    private fun blend(fg: Color, bg: Color, alpha: Float): Color = Color(
        red = fg.red * alpha + bg.red * (1f - alpha),
        green = fg.green * alpha + bg.green * (1f - alpha),
        blue = fg.blue * alpha + bg.blue * (1f - alpha),
        alpha = 1f,
    )

    private fun auditParesDeTexto(spec: PaletteSpec, dark: Boolean) {
        val s = spec.scheme(dark)
        val mode = if (dark) "dark" else "light"
        assertAa(spec.label, mode, "onSurface/surface", s.onSurface, s.surface)
        assertAa(spec.label, mode, "onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
        assertAa(spec.label, mode, "secondary/surface (badges)", s.secondary, s.surface)
        assertAa(spec.label, mode, "tertiary/surface (links)", s.tertiary, s.surface)
        assertAa(spec.label, mode, "error/surface (avisos)", s.error, s.surface)
        assertAa(spec.label, mode, "onPrimary/primary (botões)", s.onPrimary, s.primary)
        assertAa(
            spec.label, mode,
            "onPrimaryContainer/primaryContainer (avatar)",
            s.onPrimaryContainer, s.primaryContainer
        )
    }

    private fun auditCitacaoECodigo(spec: PaletteSpec, dark: Boolean) {
        val s = spec.scheme(dark)
        val mode = if (dark) "dark" else "light"
        assertAa(
            spec.label, mode, "citação",
            s.onSurfaceVariant, blend(s.surfaceVariant, s.surface, 0.35f)
        )
        assertAa(spec.label, mode, "código inline", s.onSurface, s.surfaceVariant)
    }

    @Test
    fun todasAsPaletas_paresDeTextoPassamAa() {
        AppPalettes.all.forEach { spec ->
            auditParesDeTexto(spec, dark = false)
            auditParesDeTexto(spec, dark = true)
        }
    }

    @Test
    fun todasAsPaletas_citacaoECodigoPassamAa() {
        AppPalettes.all.forEach { spec ->
            auditCitacaoECodigo(spec, dark = false)
            auditCitacaoECodigo(spec, dark = true)
        }
    }
}
