package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.util.CitationStatus
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.SourceStatus
import com.bettertalker.app.data.util.TextStatus
import com.bettertalker.app.data.util.editionStatusOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bloco de referências do prompt: motivo + ação + instrução explícita para
 * nunca dizer que a referência não existe nem inventar/usar outra edição.
 */
class ChatPromptTest {

    private fun pub(
        ref: String,
        status: SourceStatus,
        textStatus: TextStatus = com.bettertalker.app.data.util.defaultTextStatus(status),
        pubKey: String = "w25.07",
        editionKey: String = "w|2025|7",
        hint: String? = null,
    ) = NoteSource(
        ref = ref,
        kind = SourceKind.PUBLICATION,
        citation = CitationStatus(editionStatusOf(status), textStatus),
        hint = hint,
        editionKey = editionKey,
        publicationKey = pubKey,
        publicationLabel = pubKey,
    )

    private fun sources(vararg items: NoteSource) = NoteSources(
        sources = items.toList(),
        resolved = items.count { it.status == SourceStatus.RESOLVED },
        total = items.size,
    )

    @Test
    fun inventarioInstruiANaoDizerQueAReferenciaNaoExiste() {
        val text = buildNoteRefsInventory(
            sources(pub("w25.07 § 8", SourceStatus.EDITION_MISSING)),
        )
        assertTrue(text.contains("NÃO diga que uma referência não existe"))
        assertTrue(text.contains("NUNCA invente o conteúdo da fonte nem use outra edição"))
    }

    @Test
    fun inventarioIncluiMotivoEAcaoDiferentesPorRef() {
        val text = buildNoteRefsInventory(
            sources(
                pub("w25.07 § 8", SourceStatus.EDITION_MISSING),
                pub("w25.07 § 999", SourceStatus.TEXT_NOT_FOUND),
                pub(
                    "g 1/16",
                    SourceStatus.EDITION_MISSING,
                    textStatus = TextStatus.AMBIGUOUS,
                    pubKey = "g",
                    editionKey = "g|2016|1",
                    hint = "g 1/16 pode ser janeiro/2016 ou edição 1/2016.",
                ),
            )
        )
        assertTrue(text.contains("w25.07 § 8 — edição ausente do acervo — ação: baixar edição"))
        assertTrue(
            text.contains(
                "w25.07 § 999 — edição no acervo; trecho não encontrado — ação: reindexar",
            ),
        )
        assertTrue(text.contains("g 1/16 — g 1/16 pode ser janeiro/2016"))
        assertTrue(text.contains("ação: confirmar edição"))
        assertFalse(text.contains("w25.07 § 999 — edição ausente do acervo"))
    }

    @Test
    fun inventarioMarcaBaixandoEIndexandoComAguardar() {
        val text = buildNoteRefsInventory(
            sources(
                pub("w25.08 § 1", SourceStatus.DOWNLOADING, pubKey = "w25.08", editionKey = "w|2025|8"),
                pub("w25.09 § 2", SourceStatus.INDEXING, pubKey = "w25.09", editionKey = "w|2025|9"),
            ),
        )
        assertTrue(text.contains("w25.08 § 1 — edição baixando"))
        assertTrue(text.contains("w25.09 § 2 — edição indexando"))
        assertTrue(text.contains("ação: aguardar"))
    }

    @Test
    fun buildChatPromptMantemBlocoDeReferenciasSemTexto() {
        val prompt = buildChatPrompt(
            message = "O que diz w24.01 § 3?",
            history = emptyList(),
            isFirstMessage = true,
            pack = ContextPack(
                contentSources = emptyList(),
                trainingSources = emptyList(),
                unavailableRefs = listOf("w24.01 § 3 — edição ausente do acervo — ação: baixar edição"),
            ),
            blockTitle = null,
            blockMinutes = null,
            blockText = "",
        )
        assertTrue(prompt.contains("REFERÊNCIAS DO ESBOÇO SEM TEXTO NO ACERVO"))
        assertTrue(prompt.contains("NÃO diga que a referência não existe"))
        assertTrue(prompt.contains("NUNCA invente o conteúdo nem use outra edição"))
        assertTrue(prompt.contains("w24.01 § 3"))
        assertTrue(prompt.contains("ação: baixar edição"))
    }
}
