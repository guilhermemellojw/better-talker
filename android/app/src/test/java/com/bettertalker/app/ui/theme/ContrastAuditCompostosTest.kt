package com.bettertalker.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * exp/chat-contrast (2026-10-22) — camada 2 da auditoria de contraste: os
 * **elementos compostos** (não só os pares de tokens).
 *
 * O [ContrastAuditTest] audita 13 pares de tokens contra `surface`. Ele NÃO
 * cobre: texto com alpha sobre superfície, fundo = `surfaceVariant` com alpha,
 * `secondaryContainer` como fundo, cor hardcoded, e a **textura de fundo** (que
 * tinta a superfície e muda o contraste real).
 *
 * Cada elemento é auditado em 2 cenários de fundo:
 * 1. sem textura;
 * 2. com a textura no MÁXIMO (teto [MAX_TEXTURE_ALPHA]).
 *
 * Achados que esta camada pegou (e que a camada 1 não via):
 * - "Pensando…" com alpha 0,4 no pulso → 1,83:1 (sumia)
 * - check verde fixo #2E7D32 → 2,74:1 no dark
 * - placeholder do editor com alpha 0,5 → 2,29:1
 */
class ContrastAuditCompostosTest {

    private val aa = 4.5

    /** Elemento composto: cor do texto + fundo real (tokens + alphas). */
    private data class Elemento(
        val nome: String,
        val fg: String,
        val bg: String,
        val fgAlpha: Float = 1f,
        val bgAlpha: Float = 1f,
        /** O fundo é o da TELA (texturizado)? Senão, é uma superfície opaca. */
        val fundoDaTela: Boolean = false,
        /** Mínimo WCAG: 4,5 para texto; 3,0 para ícone/elemento gráfico. */
        val minRatio: Double = 4.5,
    )

    private val elementos = listOf(
        Elemento("bolha do chat (texto)", "onSurface", "surface"),
        Elemento("refs da nota - rotulo", "onSurface", "surface"),
        Elemento("refs - metadados (secondary)", "secondary", "surface"),
        Elemento("\"Sugestao criativa\"", "secondary", "surface"),
        Elemento("\"pensou Xs\"", "onSurfaceVariant", "surface"),
        Elemento("aviso do verifier (error)", "error", "surface"),
        Elemento("evidencia \"Fontes e apoio\"", "onSurface", "surface"),
        Elemento("bola do usuario (onSurface)", "onSurface", "secondaryContainer"),
        Elemento("bola do usuario (onSecondaryContainer)", "onSecondaryContainer", "secondaryContainer"),
        Elemento("painel \"Prepare o Copilot\"", "onSurface", "secondaryContainer"),
        Elemento("badge do card de nota", "onSecondaryContainer", "secondaryContainer"),
        Elemento("texto no bloco de raciocinio", "onSurface", "surfaceVariant", bgAlpha = 0.4f),
        Elemento("\"pensou\" no raciocinio", "onSurfaceVariant", "surfaceVariant", bgAlpha = 0.4f),
        Elemento("placeholder de busca", "onSurfaceVariant", "surface"),
        Elemento("\"Pensando...\" (pulso)", "onSurfaceVariant", "surfaceVariant", bgAlpha = 0.4f),
        Elemento("check do raciocinio (onSurfaceVariant)", "onSurfaceVariant", "surfaceVariant", bgAlpha = 0.4f, minRatio = 3.0),
        Elemento("placeholder do editor", "onSurfaceVariant", "surface"),
        Elemento("texto sobre a tela texturizada", "onSurface", "surface", fundoDaTela = true),
        // exp/chat-contrast (2026-10-22) — v2.5.4: indicadores finos.
        // O primary (amarelo) como borda/cursor/barra dava 1,23–1,55:1 no
        // light (Samsung) e 2,25–2,84:1 (Quente) — abaixo dos 3:1 da WCAG
        // 1.4.11. O tertiary passa nas 27 paletas (pior 3,62 light / 4,82 dark).
        Elemento("indicador fino - borda da secao ativa", "tertiary", "surfaceVariant", bgAlpha = 0.35f, minRatio = 3.0),
        Elemento("indicador fino - barra da citacao", "tertiary", "surface", minRatio = 3.0),
        Elemento("indicador fino - cursor do editor", "tertiary", "surfaceVariant", bgAlpha = 0.35f, minRatio = 3.0),
        // textos que estavam em primary (fill) e passaram a secondary
        Elemento("texto de confirmacao (secundario)", "secondary", "surface"),
    )

    private fun token(scheme: androidx.compose.material3.ColorScheme, name: String, dark: Boolean): Color =
        when (name) {
            "error" -> if (dark) Color(0xFFF2B8B5) else Color(0xFFB3261E)
            else -> when (name) {
                "primary" -> scheme.primary
                "onSurface" -> scheme.onSurface
                "surface" -> scheme.surface
                "surfaceVariant" -> scheme.surfaceVariant
                "onSurfaceVariant" -> scheme.onSurfaceVariant
                "secondary" -> scheme.secondary
                "tertiary" -> scheme.tertiary
                "secondaryContainer" -> scheme.secondaryContainer
                "onSecondaryContainer" -> scheme.onSecondaryContainer
                else -> error("token desconhecido: $name")
            }
        }

    private fun blend(fg: Color, bg: Color, alpha: Float): Color = Color(
        red = fg.red * alpha + bg.red * (1f - alpha),
        green = fg.green * alpha + bg.green * (1f - alpha),
        blue = fg.blue * alpha + bg.blue * (1f - alpha),
        alpha = 1f,
    )

    private fun assertElemento(spec: PaletteSpec, dark: Boolean, elemento: Elemento, comTextura: Boolean) {
        val s = spec.scheme(dark)
        val surface = token(s, "surface", dark)
        val ink = token(s, "onSurface", dark)
        // fundo base
        var bg = token(s, elemento.bg, dark)
        if (elemento.bgAlpha < 1f) bg = blend(bg, surface, elemento.bgAlpha)
        // a textura só tinta o fundo da TELA (as superfícies são opacas)
        if (comTextura && elemento.fundoDaTela) bg = blend(ink, surface, MAX_TEXTURE_ALPHA)
        // texto
        var fg = token(s, elemento.fg, dark)
        if (elemento.fgAlpha < 1f) fg = blend(fg, bg, elemento.fgAlpha)
        val r = contrastRatio(fg, bg)
        val cenario = if (comTextura) "com textura" else "sem textura"
        val min = elemento.minRatio
        assertTrue(
            "${spec.label}/${if (dark) "dark" else "light"} — ${elemento.nome} ($cenario): " +
                "${"%.2f".format(r)} < ${"%.1f".format(min)}",
            r >= min,
        )
    }

    @Test
    fun elementosCompostos_passamAa_emTodasAsPaletas() {
        AppPalettes.all.forEach { spec ->
            listOf(false, true).forEach { dark ->
                elementos.forEach { e ->
                    assertElemento(spec, dark, e, comTextura = false)
                    assertElemento(spec, dark, e, comTextura = true)
                }
            }
        }
    }
}
