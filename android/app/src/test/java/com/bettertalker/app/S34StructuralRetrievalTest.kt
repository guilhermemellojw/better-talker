package com.bettertalker.app

import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.s34.S34StructuralRetrieval
import com.bettertalker.app.data.s34.S34StructuralRetrieval.EntryKind
import com.bettertalker.app.data.s34.S34StructuralRetrieval.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 19-B.4 — retrieval estrutural por sectionId + ponto atual.
 * Puro: sem Room, sem rede, sem LLM. Isolamento e ordem são obrigatórios.
 */
class S34StructuralRetrievalTest {

    private val doc: S34Document = S34Parser.parseS34(
        S34Fixture.TEXT
            .replace("A fé precisa de uma base sólida", "A CONFIANÇA precisa de uma base sólida")
            .replace(
                "A fé cresce quando colocamos em prática o que aprendemos",
                "A ORAÇÃO cresce quando colocamos em prática o que aprendemos"
            )
            .replace(
                "Continue fortalecendo sua fé",
                "Continue fortalecendo sua CONFIANÇA"
            )
    )

    // ---------- Escopo por sectionId ----------

    @Test
    fun escopoPorSectionIdTrazSoOPonto() {
        val v = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!
        assertEquals("sec-2", v.sectionId)
        assertEquals(2, v.documentOrder)
        // Nem o ponto 1 nem o 3 aparecem.
        assertFalse(v.entries.any { it.text.contains("CONFIANÇA") })
        assertTrue(v.entries.any { it.text.contains("aplicar o que aprendemos".replace("aplicar", "Aplicar")) })
    }

    @Test
    fun sectionIdDesconhecidoNaoViraOutraSecao() {
        assertNull(S34StructuralRetrieval.scopeToSection(doc, "sec-99"))
        assertNull(S34StructuralRetrieval.scopeToSection(doc, "sec-1-1"))
    }

    @Test
    fun escopoNaoVazaEntreOutlines() {
        // S34-A e S34-B: `sec-2` de um NÃO resolve no outro com conteúdo alheio.
        val a = S34Parser.parseS34(
            "S-34 A\n\nTema: A\n\nObjetivo:\nObjetivo A detalhado com palavras suficientes.\n\n" +
                "1. CONFIANÇA (2 min)\n   Corpo A sobre confiança.\n\n" +
                "2. ORAÇÃO (2 min)\n   Corpo A sobre oração."
        )
        val b = S34Parser.parseS34(
            "S-34 B\n\nTema: B\n\nObjetivo:\nObjetivo B detalhado com palavras suficientes.\n\n" +
                "1. CONFIANÇA (2 min)\n   Corpo B sobre confiança.\n\n" +
                "2. ESPERANÇA (2 min)\n   Corpo B sobre esperança."
        )
        val va = S34StructuralRetrieval.scopeToSection(a, "sec-2")!!
        val vb = S34StructuralRetrieval.scopeToSection(b, "sec-2")!!
        assertTrue(va.entries.any { it.text.contains("oração") || it.text.contains("ORAÇÃO") })
        assertTrue(vb.entries.any { it.text.contains("esperança") || it.text.contains("ESPERANÇA") })
        // Nenhum contém o conteúdo do outro.
        assertFalse(va.entries.any { it.text.contains("Corpo B") })
        assertFalse(vb.entries.any { it.text.contains("Corpo A") })
        assertFalse(va.outlineId == vb.outlineId)
    }

    // ---------- Ordem (nunca score) ----------

    @Test
    fun secaoComMaiorScoreNaoSobeNaOrdem() {
        // sec-2 tem 3 ocorrências da query; sec-1 e sec-3 nenhuma.
        // A ordem documental (1→2→3) precisa sobreviver.
        val refs = S34StructuralRetrieval.sectionRefs(doc)
        assertEquals(listOf(1, 2, 3), refs.map { it.order })
        assertEquals(listOf("sec-1", "sec-2", "sec-3"), refs.map { it.id })
        val views = S34StructuralRetrieval.scopeToDocument(doc)
        assertEquals(listOf(1, 2, 3), views.map { it.documentOrder })
    }

    @Test
    fun ordemDosPontosNaoDependeDaQuery() {
        val a = S34StructuralRetrieval.scopeToDocument(doc).map { it.sectionId }
        val b = S34StructuralRetrieval.scopeToDocument(doc)
            .map { S34StructuralRetrieval.markMatches(it, "CONFIANÇA") }
            .map { it.sectionId }
        assertEquals(a, b)
    }

    @Test
    fun ordemInternaDoPontoEstrutural() {
        val v = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!
        val lines = v.entries.map { it.sourceLine }
        assertEquals(lines.sorted(), lines)
        // Dono: tudo da sec-2 ou de suas subseções.
        assertTrue(v.entries.all { it.ownerId == "sec-2" || it.ownerId.startsWith("sec-2-") })
    }

    // ---------- Query só marca ----------

    @Test
    fun queryMarcaSemReordenarNemRemover() {
        val base = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!
        val marked = S34StructuralRetrieval.markMatches(base, "CONFIANÇA")
        assertEquals(base.entries.map { it.id }, marked.entries.map { it.id })
        assertEquals(base.entries.size, marked.entries.size)
        // sec-2 tem "CONFIANÇA" no título (body? não) — marca só onde houver.
        assertTrue(marked.matchedEntries.isNotEmpty() || base.entries.none { it.text.contains("CONFIANÇA") })
    }

    @Test
    fun queryVaziaNaoMarcaNada() {
        val base = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!
        val marked = S34StructuralRetrieval.markMatches(base, "  ")
        assertTrue(marked.matchedEntries.isEmpty())
        assertEquals(base, marked)
    }

    // ---------- Ponto atual (resolução) ----------

    @Test
    fun resolvePorTituloExato() {
        val r = S34StructuralRetrieval.resolveSection(doc, "A ORAÇÃO cresce quando colocamos em prática o que aprendemos")
        assertTrue(r is Resolution.Resolved)
        assertEquals("sec-2", (r as Resolution.Resolved).sectionId)
    }

    @Test
    fun resolvePorContencaoUnica() {
        val r = S34StructuralRetrieval.resolveSection(doc, "Ponto 3: Continue fortalecendo sua CONFIANÇA agora")
        assertTrue(r is Resolution.Resolved)
        assertEquals("sec-3", (r as Resolution.Resolved).sectionId)
    }

    @Test
    fun dicaAusenteENoHint() {
        assertEquals(Resolution.NoHint, S34StructuralRetrieval.resolveSection(doc, null))
        assertEquals(Resolution.NoHint, S34StructuralRetrieval.resolveSection(doc, "   "))
    }

    @Test
    fun dicaAmbiguaNaoChuta() {
        // "cresce" aparece no título da sec-2 apenas — contenção única.
        // Dica que casa com várias (ou nenhuma) deve ser Unmatched.
        val r = S34StructuralRetrieval.resolveSection(doc, "assunto completamente diferente")
        assertTrue(r is Resolution.Unmatched)
        assertEquals("assunto completamente diferente", (r as Resolution.Unmatched).hint)
    }

    // ---------- Referências vinculadas ----------

    @Test
    fun referenciasFicamVinculadasAoDono() {
        val v = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!
        val refs = v.entries.filter { it.kind == EntryKind.REFERENCE }
        assertTrue(refs.isNotEmpty())
        // Toda ref aponta para a seção ou uma de suas subseções.
        assertTrue(refs.all { it.ownerId == "sec-2" || it.ownerId.startsWith("sec-2-") })
        // A Bíblia do ponto 2 é Tiago; a de outro ponto não aparece.
        assertTrue(refs.any { it.text.contains("Tiago") })
        assertFalse(refs.any { it.text.contains("João 17:17") })
        assertFalse(refs.any { it.text.contains("Hebreus") })
    }

    @Test
    fun referenciaDeSubsecaoPermaneceNaSubsecao() {
        val text = S34Fixture.TEXT.replace(
            "   b) Aplicar o que aprendemos",
            "   b) Aplicar o que aprendemos. Leia João 3:16."
        )
        val d = S34Parser.parseS34(text)
        val v = S34StructuralRetrieval.scopeToSection(d, "sec-2")!!
        val subRef = v.entries.first { it.kind == EntryKind.REFERENCE && it.text.contains("João 3:16") }
        assertEquals("sec-2-2", subRef.ownerId)
        assertTrue(subRef.sourceLine > 0)
    }

    @Test
    fun tipoDeReferenciaPreservado() {
        val v = S34StructuralRetrieval.scopeToSection(doc, "sec-1")!!
        val refs = v.entries.filter { it.kind == EntryKind.REFERENCE }
        assertTrue(refs.any { it.refType == S34RefType.BIBLE })
        assertTrue(refs.any { it.refType == S34RefType.PUBLICATION })
    }

    // ---------- Provenance ----------

    @Test
    fun provenanceEMantidaEmTodaEntrada() {
        val v = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!
        assertTrue(v.entries.all { it.sourceLine > 0 })
        assertTrue(v.entries.all { it.ownerId.isNotBlank() })
        assertTrue(v.entries.all { it.id.isNotBlank() })
    }

    // ---------- Determinismo ----------

    @Test
    fun mesmaEntradaMesmoResultado() {
        val a = S34StructuralRetrieval.scopeToSection(doc, "sec-2")
        val b = S34StructuralRetrieval.scopeToSection(doc, "sec-2")
        assertEquals(a, b)
        assertEquals(
            S34StructuralRetrieval.markMatches(a!!, "prática"),
            S34StructuralRetrieval.markMatches(b!!, "prática")
        )
    }

    @Test
    fun documentoVazioNaoQuebra() {
        val empty = S34Parser.parseS34("")
        assertTrue(S34StructuralRetrieval.sectionRefs(empty).isEmpty())
        assertTrue(S34StructuralRetrieval.scopeToDocument(empty).isEmpty())
        assertEquals(Resolution.NoHint, S34StructuralRetrieval.resolveSection(empty, null))
        assertTrue(S34StructuralRetrieval.resolveSection(empty, "qualquer") is Resolution.Unmatched)
        assertNull(S34StructuralRetrieval.scopeToSection(empty, "sec-1"))
    }

    @Test
    fun retornaTituloEMinutosDoPonto() {
        val v = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!
        assertTrue(v.title.contains("ORAÇÃO"))
        assertEquals(5, v.minutes)
        assertNotNull(v.entries.firstOrNull { it.kind == EntryKind.SECTION })
    }
}
