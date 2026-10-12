package com.bettertalker.app.data.planning

import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B/C/D (prosa no dossiê + título/tipo no system): a seção focada leva
 * ao prompt o que já foi escrito (contentHtml/developedHtml/seleção),
 * não só metadados — e o bloco ## DISCURSO identifica nota e tipo.
 */
class DossierProseTest {

    private fun section(
        id: String = "s1",
        contentHtml: String = "",
    ) = SpeechSection(
        id = id, noteId = "n1", order = 0, role = SectionRole.BODY,
        title = "Fé que age", minutes = 5, contentHtml = contentHtml,
        bibleRefs = emptyList(), publicationRefs = emptyList(),
        methodPrinciple = null, createdAt = 1L, updatedAt = 1L,
    )

    private fun subPoint(
        id: String = "sp1",
        developedHtml: String = "",
    ) = SubPoint(
        id = id, sectionId = "s1", order = 0, outlineText = "ponto $id",
        bibleRefs = emptyList(), publicationRefs = emptyList(),
        instruction = null, developedHtml = developedHtml,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun dossier(
        section: SpeechSection = section(),
        subPoints: List<SubPoint> = emptyList(),
        selectedText: String? = null,
        noteTitle: String? = null,
        discourseType: String? = null,
    ) = Dossier(
        currentSection = section,
        currentSubPoint = null,
        selectedText = selectedText,
        fullContentHtml = "",
        overview = emptyList(),
        bibleTexts = emptyList(),
        publicationTexts = emptyList(),
        methodPrinciples = emptyList(),
        unresolvedRefs = emptyList(),
        transitionContext = null,
        sectionSubPoints = subPoints,
        noteTitle = noteTitle,
        discourseType = discourseType,
    )

    private val builder = DefaultDossierPromptBuilder()

    @Test
    fun secaoComContentHtml_trazTextoAtualDaSecao() {
        val prompt = builder.buildContextBlock(
            dossier(section = section(contentHtml = "<p>Texto já escrito do tópico.</p>"))
        )

        assertTrue(prompt.contains("## TEXTO ATUAL DA SEÇÃO (o que já foi escrito)"))
        assertTrue(prompt.contains("Texto já escrito do tópico."))
        assertFalse(prompt.contains("<p>"))
    }

    @Test
    fun secaoSemContentHtml_omiteBlocoB1() {
        val prompt = builder.buildContextBlock(dossier())

        assertFalse(prompt.contains("TEXTO ATUAL DA SEÇÃO"))
    }

    @Test
    fun subPontoComDevelopedHtml_trazDesenvolvimentoAtual() {
        val prompt = builder.buildContextBlock(
            dossier(subPoints = listOf(subPoint(developedHtml = "<p>Desenvolvimento do ponto.</p>")))
        )

        assertTrue(prompt.contains("### Sub-ponto 1 — desenvolvimento atual"))
        assertTrue(prompt.contains("Desenvolvimento do ponto."))
    }

    @Test
    fun selectedTextPresente_trazTrechoSelecionado() {
        val prompt = builder.buildContextBlock(
            dossier(selectedText = "trecho que o usuário marcou")
        )

        assertTrue(prompt.contains("## TRECHO SELECIONADO"))
        assertTrue(prompt.contains("trecho que o usuário marcou"))
    }

    @Test
    fun contentHtmlLongo_truncaEm4000ComMarcador() {
        val long = "<p>" + "x".repeat(5000) + "</p>"
        val prompt = builder.buildContextBlock(
            dossier(section = section(contentHtml = long))
        )

        assertTrue(prompt.contains("… [truncado]"))
        val line = prompt.lines().first { it.startsWith("x") }
        assertTrue("trecho tinha ${line.length} chars", line.length <= 4000 + "… [truncado]".length)
    }

    @Test
    fun noteTitleEDiscourseType_trazBlocoDiscurso() {
        val prompt = builder.buildContextBlock(
            dossier(
                section = section(contentHtml = "<p>Prosa.</p>"),
                noteTitle = "# 35. Viver para sempre?",
                discourseType = "S34_DISCOURSE",
            )
        )

        assertTrue(prompt.contains("## DISCURSO"))
        // G: o "# " do markdown do S-34 é limpo no título.
        assertTrue(prompt.contains("Título: 35. Viver para sempre?"))
        assertFalse(prompt.contains("Título: # 35."))
        assertTrue(prompt.contains("Tipo: S34_DISCOURSE"))
    }

    @Test
    fun tituloOuTipoNulos_omiteBlocoDiscurso() {
        assertFalse(
            builder.buildContextBlock(dossier()).contains("## DISCURSO")
        )
        assertFalse(
            builder.buildContextBlock(dossier(noteTitle = "Só título")).contains("## DISCURSO")
        )
        assertFalse(
            builder.buildContextBlock(dossier(discourseType = "S34_DISCOURSE")).contains("## DISCURSO")
        )
    }
}
