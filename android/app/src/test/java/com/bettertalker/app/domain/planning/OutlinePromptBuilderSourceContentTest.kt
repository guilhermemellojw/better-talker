package com.bettertalker.app.domain.planning

import com.bettertalker.app.data.planning.DefaultOutlinePromptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5b (Modo C) — o prompt muda de "crie um esboço sobre X" para "estructure
 * este conteúdo" quando `sourceContent` está presente; sem ele, o caminho
 * theme-driven do #3c fica intacto.
 */
class OutlinePromptBuilderSourceContentTest {

    private fun request(source: String?) = OutlineGenerationRequest(
        id = "r1",
        theme = "Seção 1",
        totalMinutes = 30,
        audience = Audience.GENERAL,
        angle = OutlineAngle.PRACTICAL,
        bibleRefs = listOf("João 3:16"),
        publicationRefs = listOf(PublicationRef("w13 15/5")),
        methodPrinciples = emptyList(),
        sourceContent = source,
    )

    @Test
    fun comSourceContent_promptEstruturaOConteudo() {
        val prompt = DefaultOutlinePromptBuilder().build(
            request("O artigo fala sobre a esperança.\n[Leia João 3:16.]"),
        )
        assertTrue(prompt.contains("Estruture este conteúdo em seções com tempos e textos-âncora."))
        assertTrue(prompt.contains("O artigo fala sobre a esperança."))
        assertTrue(prompt.contains("Preserve as referências já citadas no conteúdo."))
        assertFalse(prompt.contains("Gere um esboço de discurso"))
        // O tema derivado do conteúdo: o título da seção NÃO vira tema.
        assertFalse(prompt.contains("Tema: Seção 1"))
    }

    @Test
    fun semSourceContent_promptThemeDrivenIntacto() {
        val prompt = DefaultOutlinePromptBuilder().build(request(null))
        assertTrue(prompt.contains("Gere um esboço de discurso em português (PT-BR)."))
        assertTrue(prompt.contains("Tema: Seção 1"))
        assertFalse(prompt.contains("Estruture este conteúdo"))
    }

    @Test
    fun semMetodos_espacoAposDoisPontos() {
        // F5c (cosmético): ": (nenhum fornecido)" nos dois caminhos do prompt.
        val theme = DefaultOutlinePromptBuilder().build(request(null))
        val source = DefaultOutlinePromptBuilder().build(request("Conteúdo colado."))
        assertTrue(theme.contains("aplicar: (nenhum fornecido)"))
        assertFalse(theme.contains("aplicar:(nenhum fornecido)"))
        assertTrue(source.contains("aplicar: (nenhum fornecido)"))
        assertFalse(source.contains("aplicar:(nenhum fornecido)"))
    }

    @Test
    fun semRefs_espacoAposDoisPontos() {
        // F5d (cosmético irmão): espaçamento também nas linhas de bíblia/publicações.
        val semRefs = request(null).copy(bibleRefs = emptyList(), publicationRefs = emptyList())
        val theme = DefaultOutlinePromptBuilder().build(semRefs)
        assertTrue(theme.contains("estas): (nenhuma fornecida)"))
        assertFalse(theme.contains("estas):(nenhuma fornecida)"))
        val source = DefaultOutlinePromptBuilder().build(
            semRefs.copy(sourceContent = "Conteúdo colado."),
        )
        assertTrue(source.contains("estas): (nenhuma encontrada)"))
        assertFalse(source.contains("estas):(nenhuma encontrada)"))
    }

    @Test
    fun sourceContent_truncadoEm12k() {
        val long = "x".repeat(DefaultOutlinePromptBuilder.MAX_SOURCE_CHARS + 500)
        val prompt = DefaultOutlinePromptBuilder().build(request(long))
        assertTrue(prompt.contains("…(conteúdo truncado)"))
        assertFalse(prompt.contains(long))
    }
}
