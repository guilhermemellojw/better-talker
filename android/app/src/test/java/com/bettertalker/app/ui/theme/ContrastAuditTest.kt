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

    // ---------- A11y: os 4 usos corrigidos (primary → secondary/onSurfaceVariant) ----------

    @Test
    fun a11y_fonteNoChat_passaAa() {
        // "📖 Fonte" (ChatCards) fica sobre o fundo do chat (surface).
        assertAa("claro", LightColors.secondary, LightColors.surface)
        assertAa("escuro", DarkColors.secondary, DarkColors.surface)
    }

    @Test
    fun a11y_fielAoDossie_passaAa() {
        // "✓ Fiel ao dossiê" (DraftSheet) sobre o container do bottom sheet.
        assertAa("claro", LightColors.secondary, LightColors.surfaceContainerLow)
        assertAa("escuro", DarkColors.secondary, DarkColors.surfaceContainerLow)
    }

    @Test
    fun a11y_numeroDoSubPonto_passaAa() {
        // Número do sub-ponto (SectionCardEditor) sobre o card preenchido.
        assertAa("claro", LightColors.onSurfaceVariant, LightColors.surfaceContainerHighest)
        assertAa("escuro", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerHighest)
    }

    @Test
    fun a11y_miniDiscurso_passaAa() {
        // Rótulo "MINI DISCURSO" sobre o card preenchido.
        assertAa("claro", LightColors.onSurfaceVariant, LightColors.surfaceContainerHighest)
        assertAa("escuro", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerHighest)
    }

    @Test
    fun a11y_secondaryNaoBastaNoCardClaro() {
        // Documenta a escolha: no card claro (surfaceContainerHighest) o
        // secondary dá 4.23:1 — por isso esses dois usam onSurfaceVariant.
        val r = contrastRatio(LightColors.secondary, LightColors.surfaceContainerHighest)
        assertTrue("secondary no card claro: ${"%.2f".format(r)}", r < aa)
    }

    // ---------- Follow-up: 7 usos em ModelScreen/LibraryScreen ----------

    @Test
    fun a11y_modeloPronto_passaAa() {
        // "Modelo pronto ✓" sobre Card M3 padrão (surfaceContainerLow).
        assertAa("claro", LightColors.onSurfaceVariant, LightColors.surfaceContainerLow)
        assertAa("escuro", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerLow)
    }

    @Test
    fun a11y_modeloPresente_passaAa() {
        // "Modelo presente ✓ (instalado)" sobre Card M3 padrão.
        assertAa("claro", LightColors.onSurfaceVariant, LightColors.surfaceContainerLow)
        assertAa("escuro", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerLow)
    }

    @Test
    fun a11y_chaveGroq_passaAa() {
        // "Chave Groq configurada ✓" sobre Card M3 padrão.
        assertAa("claro", LightColors.onSurfaceVariant, LightColors.surfaceContainerLow)
        assertAa("escuro", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerLow)
    }

    @Test
    fun a11y_chaveGemini_passaAa() {
        // "Chave configurada ✓" (Gemini) sobre Card M3 padrão.
        assertAa("claro", LightColors.onSurfaceVariant, LightColors.surfaceContainerLow)
        assertAa("escuro", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerLow)
    }

    @Test
    fun a11y_dicaVincular_passaAa() {
        // "Toque Vincular…" sobre o fundo da Biblioteca (surface).
        assertAa("claro", LightColors.secondary, LightColors.surface)
        assertAa("escuro", DarkColors.secondary, DarkColors.surface)
    }

    @Test
    fun a11y_tituloCategoria_passaAa() {
        // Título da categoria do catálogo sobre o fundo (surface).
        assertAa("claro", LightColors.secondary, LightColors.surface)
        assertAa("escuro", DarkColors.secondary, DarkColors.surface)
    }

    @Test
    fun a11y_baseSlot_passaAa() {
        // "Base: …" sobre Card M3 padrão do acervo.
        assertAa("claro", LightColors.onSurfaceVariant, LightColors.surfaceContainerLow)
        assertAa("escuro", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerLow)
    }

    // ---------- 2 usos finais (ModelScreen) ----------

    @Test
    fun a11y_gemmaLocalDescricao_passaAa() {
        // Descrição do Gemma local (ModelScreen) sobre Card M3 — onSurfaceVariant
        // passa nos dois tokens possíveis de container (Low/Highest).
        assertAa("claro/low", LightColors.onSurfaceVariant, LightColors.surfaceContainerLow)
        assertAa("claro/highest", LightColors.onSurfaceVariant, LightColors.surfaceContainerHighest)
        assertAa("escuro/low", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerLow)
        assertAa("escuro/highest", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerHighest)
    }

    @Test
    fun a11y_deepSeekStatusOnline_passaAa() {
        // "Status: ONLINE" (ModelScreen) sobre Card M3.
        assertAa("claro/low", LightColors.onSurfaceVariant, LightColors.surfaceContainerLow)
        assertAa("claro/highest", LightColors.onSurfaceVariant, LightColors.surfaceContainerHighest)
        assertAa("escuro/low", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerLow)
        assertAa("escuro/highest", DarkColors.onSurfaceVariant, DarkColors.surfaceContainerHighest)
    }
}
