package com.bettertalker.app.data.export

import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 (Exportação) — builder puro do HTML de exportação do discurso.
 * Sem Room/Android: seções e sub-pontos de domínio na mão.
 */
class ExportHtmlBuilderTest {

    private fun section(
        role: SectionRole,
        order: Int,
        title: String,
        minutes: Int = 5,
        contentHtml: String = "",
        objective: String? = null,
        bibleRefs: List<String> = emptyList(),
        publicationRefs: List<PublicationRef> = emptyList(),
        agreedApproach: String? = null,
    ) = SpeechSection(
        id = "s$order", noteId = "n1", order = order, role = role, title = title,
        minutes = minutes, contentHtml = contentHtml, bibleRefs = bibleRefs,
        publicationRefs = publicationRefs, methodPrinciple = null,
        objective = objective, agreedApproach = agreedApproach,
        createdAt = 0L, updatedAt = 0L,
    )

    private fun sub(
        sectionId: String,
        order: Int,
        outline: String = "",
        developed: String = "",
        bibleRefs: List<String> = emptyList(),
        publicationRefs: List<PublicationRef> = emptyList(),
    ) = SubPoint(
        id = "sp$order", sectionId = sectionId, order = order, outlineText = outline,
        bibleRefs = bibleRefs, publicationRefs = publicationRefs, instruction = null,
        developedHtml = developed, createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun estruturaPreservaTitulosMinutosEObjetivo() {
        val sections = listOf(
            section(SectionRole.INTRO, 0, "INTRODUÇÃO", minutes = 1,
                contentHtml = "<p>Abertura.</p>", objective = "Despertar interesse"),
            section(SectionRole.BODY, 1, "Esperança que sustenta", minutes = 5,
                contentHtml = "<p>Desenvolvimento.</p>"),
            section(SectionRole.CONCLUSION, 2, "CONCLUSÃO", minutes = 1,
                contentHtml = "<p>Fechamento.</p>"),
        )
        val html = buildExportHtml("Meu discurso", sections)
        // Ordem e formato do cabeçalho de seção.
        val iIntro = html.indexOf("<h2>INTRODUÇÃO")
        val iBody = html.indexOf("<h2>Esperança que sustenta")
        val iConc = html.indexOf("<h2>CONCLUSÃO")
        assertTrue(iIntro in 1 until iBody)
        assertTrue(iBody < iConc)
        assertTrue(html.contains("<h2>INTRODUÇÃO <span class=\"minutes\">(1 min)</span></h2>"))
        assertTrue(html.contains("<h2>Esperança que sustenta <span class=\"minutes\">(5 min)</span></h2>"))
        assertTrue(html.contains("<p class=\"objective\">Despertar interesse</p>"))
        // Sem objetivo → sem parágrafo de objetivo naquela seção.
        assertEquals(1, Regex("<p class=\"objective\">").findAll(html).count())
    }

    @Test
    fun corpoUsaMiniDiscursoQuandoPreenchido() {
        val sections = listOf(
            section(SectionRole.BODY, 0, "Tópico", contentHtml = "<p>Mini discurso real.</p>"),
        )
        val subs = mapOf("s0" to listOf(sub("s0", 0, outline = "NÃO deve aparecer")))
        val html = buildExportHtml("Discurso", sections, subs)
        assertTrue(html.contains("<p>Mini discurso real.</p>"))
        assertFalse(html.contains("NÃO deve aparecer"))
    }

    @Test
    fun fallbackParaSubPontosQuandoMiniDiscursoVazio() {
        val sections = listOf(section(SectionRole.BODY, 0, "Tópico", contentHtml = ""))
        val subs = mapOf("s0" to listOf(
            sub("s0", 0, developed = "<p>Primeiro desenvolvimento.</p>"),
            sub("s0", 1, developed = "<p>Segundo desenvolvimento.</p>"),
        ))
        val html = buildExportHtml("Discurso", sections, subs)
        assertTrue(html.contains("<p>Primeiro desenvolvimento.</p>"))
        assertTrue(html.contains("<p>Segundo desenvolvimento.</p>"))
    }

    @Test
    fun fallbackSalvaOutlineTextQuandoDesenvolvimentoVazio() {
        val sections = listOf(section(SectionRole.BODY, 0, "Tópico", contentHtml = ""))
        val subs = mapOf("s0" to listOf(sub("s0", 0, outline = "Ponto planejado do esboço")))
        val html = buildExportHtml("Discurso", sections, subs)
        assertTrue(html.contains("Ponto planejado do esboço"))
    }

    @Test
    fun refsDaSecaoEDosSubPontosDeduplicadasNoFim() {
        val sections = listOf(
            section(
                SectionRole.BODY, 0, "Tópico",
                contentHtml = "<p>Texto.</p>",
                bibleRefs = listOf("Mt 6:28"),
                publicationRefs = listOf(PublicationRef(symbol = "it")),
            ),
        )
        val subs = mapOf("s0" to listOf(
            sub("s0", 0, developed = "<p>Desenvolvimento.</p>",
                bibleRefs = listOf("Mt 6:28", "João 3:16"),
                publicationRefs = listOf(PublicationRef(symbol = "w24.04"))),
        ))
        val html = buildExportHtml("Discurso", sections, subs)
        assertTrue(html.contains("<p class=\"refs\">Referências: Mt 6:28 · it · João 3:16 · w24.04</p>"))
    }

    @Test
    fun cabecalhoComTituloEMetricas() {
        val texto = (1..130).joinToString(" ") { "palavra" }
        val sections = listOf(
            section(SectionRole.INTRO, 0, "Abertura <script>", contentHtml = "<p>$texto</p>"),
        )
        val html = buildExportHtml("Discurso de <Teste> & Cia", sections)
        // Título escapado no h1.
        assertTrue(html.contains("<h1>Discurso de &lt;Teste&gt; &amp; Cia</h1>"))
        // Métricas: 130 palavras ≈ 1 min (mesmo ritmo do WordCount).
        assertTrue(html.contains("<p class=\"metrics\">130 palavras · ≈ 1 min</p>"))
        // Título de seção também escapado.
        assertTrue(html.contains("Abertura &lt;script&gt;"))
    }

    @Test
    fun cssDeImpressaoEmbutido() {
        val html = buildExportHtml("Discurso", emptyList())
        assertTrue(html.contains("<style>"))
        assertTrue(html.contains("font-size: 18px"))
        assertTrue(html.contains("@page"))
        assertTrue(html.contains("@media print"))
        assertTrue(html.contains("page-break-after: avoid"))
    }

    @Test
    fun naoIncluiEsbocoAbordagemNemLinhaDeRaciocinio() {
        val sections = listOf(
            section(
                SectionRole.BODY, 0, "Tópico",
                contentHtml = "<p>Texto final.</p>",
                agreedApproach = "ABORDAGEM-INTERNA-NAO-EXPORTAR",
            ),
        )
        val html = buildExportHtml("Discurso", sections)
        assertTrue(html.contains("<p>Texto final.</p>"))
        assertFalse(html.contains("ABORDAGEM-INTERNA-NAO-EXPORTAR"))
    }
}
