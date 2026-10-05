package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatTurnContext
import com.bettertalker.app.data.copilot.OutlineStructureContext
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.s34.S34RefType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1 — corpus de verificação do chat remoto (CONTENT + TRAINING + S-34). */
class VerificationCorpusTest {

    private fun ev(id: String, text: String, type: SourceType = SourceType.CONTENT) = EvidenceSource(
        id = id, reference = "Fonte $id", text = text, sourceType = type,
        publication = null, section = null, paragraph = null, page = null
    )

    private fun turn(
        content: List<EvidenceSource> = emptyList(),
        training: List<EvidenceSource> = emptyList(),
        structural: OutlineStructureContext? = null,
    ) = ChatTurnContext(
        pack = ContextPack(content, training),
        prompt = "",
        evidence = emptyList(),
        structural = structural,
    )

    @Test
    fun copiaContentETrainingIgnorandoVazios() {
        val corpus = verificationCorpus(
            turn(
                content = listOf(ev("1", "Trecho de conteúdo"), ev("2", "  ")),
                training = listOf(ev("3", "Técnica de entrega", SourceType.TRAINING)),
            )
        )
        assertEquals(listOf("Trecho de conteúdo", "Técnica de entrega"), corpus)
    }

    @Test
    fun incluiObjetivoPontoSubpontosEReferenciasDoS34() {
        val sec = OutlineStructureContext.CurrentSection(
            id = "s1", order = 1, title = "Ponto 1", content = "Corpo do ponto",
            subsections = listOf(
                OutlineStructureContext.CurrentSubsection(
                    "ss1", 1, "Sub", listOf(
                        OutlineStructureContext.StructuralReference(S34RefType.BIBLE, "Leia Tiago 2:17.", "ss1", 4)
                    )
                )
            ),
            references = listOf(
                OutlineStructureContext.StructuralReference(S34RefType.BIBLE, "Leia João 3:16.", "s1", 2)
            )
        )
        val structural = OutlineStructureContext(
            outlineId = "o1", title = "Discurso", objective = "Objetivo do discurso",
            orderedSections = emptyList(), currentSection = sec,
            focusState = OutlineStructureContext.FocusState.SECTION
        )
        val corpus = verificationCorpus(turn(structural = structural))
        assertTrue(corpus.contains("Objetivo do discurso"))
        assertTrue(corpus.contains("Corpo do ponto"))
        assertTrue(corpus.contains("Sub"))
        assertTrue(corpus.contains("Leia João 3:16."))
        assertTrue(corpus.contains("Leia Tiago 2:17."))
    }

    @Test
    fun semFontesCorpusVazio() {
        assertEquals(emptyList<String>(), verificationCorpus(turn()))
    }
}
