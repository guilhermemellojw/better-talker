package com.bettertalker.app

import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.util.RefDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 19-B.2 — testes do parser S-34 → S34Document (§§20-22).
 * Fixture intacta (S34Fixture.kt inalterado); variantes inline mínimas
 * só onde a fixture não cobre o caso (ausência/ambiguidade).
 */
class S34ParserTest {

    private val doc = S34Parser.parseS34(S34Fixture.TEXT)

    // ---------- Documento básico (§20.1-4) ----------

    @Test
    fun reconheceTitulo() {
        assertEquals("Como fortalecer a fé", doc.title)
    }

    @Test
    fun reconheceObjetivo() {
        assertEquals(
            "Mostrar como a fé pode ser fortalecida por meio da Palavra de Deus.",
            doc.objective
        )
    }

    @Test
    fun reconhece3Pontos() {
        assertEquals(3, doc.sections.size)
        assertEquals(
            listOf(
                "A fé precisa de uma base sólida",
                "A fé cresce quando colocamos em prática o que aprendemos",
                "Continue fortalecendo sua fé"
            ),
            doc.sections.map { it.title }
        )
    }

    @Test
    fun preservaOrdem123() {
        assertEquals(listOf(1, 2, 3), doc.sections.map { it.order })
        assertEquals(listOf("sec-1", "sec-2", "sec-3"), doc.sections.map { it.id })
    }

    @Test
    fun preservaMinutosETemIdEstavel() {
        assertEquals(listOf(4, 5, 3), doc.sections.map { it.minutes })
        assertTrue(doc.id.startsWith("s34-"))
        assertEquals("S-34", doc.symbol)
        assertEquals(doc.id, S34Parser.parseS34(S34Fixture.TEXT).id)
    }

    // ---------- Hierarquia (§20.5-7) ----------

    @Test
    fun reconheceSubpontosAeB() {
        val subs = doc.sections[1].subsections
        assertEquals(2, subs.size)
        assertTrue(subs[0].content.contains("Estudar regularmente"))
        assertTrue(subs[1].content.contains("Aplicar o que aprendemos"))
    }

    @Test
    fun subpontoFicaNoPontoCorreto() {
        assertTrue(doc.sections[0].subsections.isEmpty())
        assertTrue(doc.sections[2].subsections.isEmpty())
        assertEquals(2, doc.sections[1].subsections.size)
        assertEquals("sec-2-1", doc.sections[1].subsections[0].id)
    }

    @Test
    fun naoMisturaSubpontosEntrePontos() {
        val allSubs = doc.sections.flatMap { it.subsections }
        assertEquals(2, allSubs.size)
        assertTrue(allSubs.all { it.id.startsWith("sec-2-") })
    }

    // ---------- Bíblia (§20.8-11) ----------

    @Test
    fun detectaJoao1717() {
        val refs = doc.sections[0].references.filter { it.type == S34RefType.BIBLE }
        assertTrue(refs.any { it.normalizedReference == "João|17|17" })
    }

    @Test
    fun detectaTiago217() {
        val refs = doc.sections[1].references.filter { it.type == S34RefType.BIBLE }
        assertTrue(refs.any { it.normalizedReference == "Tiago|2|17" })
    }

    @Test
    fun detectaHebreus1023() {
        val refs = doc.sections[2].references.filter { it.type == S34RefType.BIBLE }
        assertTrue(refs.any { it.normalizedReference == "Hebreus|10|23" })
    }

    @Test
    fun associaCadaReferenciaASecaoCorreta() {
        val bibleBySection = doc.sections.map { s ->
            s.references.filter { it.type == S34RefType.BIBLE }
                .map { it.normalizedReference }
        }
        assertTrue(bibleBySection[0].any { it.startsWith("João|") })
        assertTrue(bibleBySection[1].any { it.startsWith("Tiago|") })
        assertTrue(bibleBySection[2].any { it.startsWith("Hebreus|") })
        assertFalse(bibleBySection[0].any { it.startsWith("Tiago|") || it.startsWith("Hebreus|") })
    }

    @Test
    fun referenciaEmSubpontoVaiParaSubsecao() {
        val text = S34Fixture.TEXT.replace(
            "   b) Aplicar o que aprendemos",
            "   b) Aplicar o que aprendemos. Leia João 3:16."
        )
        val d = S34Parser.parseS34(text)
        val sub = d.sections[1].subsections[1]
        assertTrue(sub.references.any {
            it.type == S34RefType.BIBLE && it.normalizedReference == "João|3|16"
        })
        assertTrue(d.sections[1].references.none {
            it.type == S34RefType.BIBLE && it.normalizedReference == "João|3|16"
        })
    }

    // ---------- Publicações (§20.12-15) ----------

    @Test
    fun detectaW2401() {
        val refs = doc.sections[0].references.filter { it.type == S34RefType.PUBLICATION }
        assertTrue(refs.any { it.rawText.contains("w24.01") })
    }

    @Test
    fun detectaW2402() {
        val refs = doc.sections[1].references.filter { it.type == S34RefType.PUBLICATION }
        assertTrue(refs.any { it.rawText.contains("w24.02") })
    }

    @Test
    fun classificaComoPublicacao() {
        val pubs = doc.references.filter { it.type == S34RefType.PUBLICATION }
        assertTrue(pubs.size >= 2)
        assertTrue(pubs.all { it.publication != null && it.bible == null })
    }

    @Test
    fun associaPubASecaoCorreta() {
        assertTrue(doc.sections[0].references.any {
            it.type == S34RefType.PUBLICATION && it.rawText.contains("w24.01")
        })
        assertTrue(doc.sections[1].references.any {
            it.type == S34RefType.PUBLICATION && it.rawText.contains("w24.02")
        })
        assertTrue(doc.sections[2].references.none { it.type == S34RefType.PUBLICATION })
    }

    // ---------- Ordem (§20.16-17) ----------

    @Test
    fun ordemDasReferenciasEpreservada() {
        val bibleOrder = doc.references
            .filter { it.type == S34RefType.BIBLE }
            .map { it.normalizedReference }
        assertEquals(listOf("João|17|17", "Tiago|2|17", "Hebreus|10|23"), bibleOrder)
    }

    @Test
    fun ordemNaoDependeDeRetrieval() {
        // Parser nunca chama retrieval: ordem vem só do encontro textual.
        val orders = doc.sections.map { it.order }
        assertEquals(orders.sorted(), orders)
        assertEquals(orders.toSet().size, orders.size)
    }

    // ---------- Ausência (§20.18-21) ----------

    @Test
    fun semObjetivoDaNull() {
        val text = S34Fixture.TEXT.replace(
            "Objetivo:\nMostrar como a fé pode ser fortalecida por meio da Palavra de Deus.\n\n", "")
        val d = S34Parser.parseS34(text)
        assertNull(d.objective)
        assertEquals(3, d.sections.size)
    }

    @Test
    fun semReferenciasNaoFalha() {
        val text = "S-34\n\nTema: Um tema\n\n1. Primeiro ponto (2 min)\n   Texto simples.\n\n2. Segundo ponto (3 min)\n   Mais texto."
        val d = S34Parser.parseS34(text)
        assertEquals(2, d.sections.size)
        assertTrue(d.references.isEmpty())
    }

    @Test
    fun semSubpontosNaoFalha() {
        assertTrue(doc.sections[0].subsections.isEmpty())
        assertTrue(doc.sections[2].subsections.isEmpty())
    }

    @Test
    fun textoVazioNaoLanca() {
        val d = S34Parser.parseS34("")
        assertEquals("", d.title)
        assertNull(d.objective)
        assertTrue(d.sections.isEmpty())
        val d2 = S34Parser.parseS34("   \n  ")
        assertTrue(d2.sections.isEmpty())
    }

    // ---------- Conservadorismo (§20.22-25) ----------

    @Test
    fun estruturaAmbiguaNaoGeraPontoFalso() {
        val text = "S-34 — rascunho (texto sintético de teste)\n\n" +
            "Tema: Um tema qualquer\n\n" +
            "Objetivo:\nRefletir sobre algo importante para a assistência presente.\n\n" +
            "Este parágrafo fala de um assunto sem número e sem tempo marcado.\n" +
            "Outro parágrafo continua a reflexão com calma e ordem.\n"
        val d = S34Parser.parseS34(text)
        assertTrue(d.sections.isEmpty())
        assertEquals("Refletir sobre algo importante para a assistência presente.", d.objective)
    }

    @Test
    fun textoNaoReconhecidoEpreservado() {
        assertTrue(doc.headerLines.any { it.contains("S-34") })
        assertTrue(doc.sections[0].content.contains("base sólida") ||
            doc.sections[0].title.contains("base sólida"))
    }

    @Test
    fun parserNaoCriaIntroducaoNemConclusao() {
        // Pin de schema sem reflection de valores (JPMS bloqueia setAccessible
        // em testes JVM): só nomes de campos das classes do modelo.
        val classes = listOf(
            com.bettertalker.app.data.s34.S34Document::class.java,
            com.bettertalker.app.data.s34.S34Section::class.java,
            com.bettertalker.app.data.s34.S34Subsection::class.java,
            com.bettertalker.app.data.s34.S34Reference::class.java,
            RefDetector.BibleRef::class.java,
            RefDetector.DetectedRef::class.java
        )
        val names = classes.flatMap { c ->
            c.declaredFields.filter { !it.isSynthetic }.map { it.name }
        }.toSet()
        assertFalse(names.any { it.contains("intro", ignoreCase = true) })
        assertFalse(names.any { it.contains("conclu", ignoreCase = true) })
    }

    // ---------- Provenance (§20.26-27) ----------

    @Test
    fun secoesMantemOrigem() {
        assertTrue(doc.sections.all { it.source == "S34" })
        assertTrue(doc.sections.all { it.sourceLine > 0 })
        // Linha aponta para o cabeçalho do ponto no texto original.
        val lines = S34Fixture.TEXT.split("\n")
        doc.sections.forEach { s ->
            assertTrue(lines[s.sourceLine - 1].contains(s.title.take(10)))
        }
    }

    @Test
    fun referenciasMantemOrigem() {
        assertTrue(doc.references.all { it.source == "S34" && it.sourceLine > 0 })
        val lines = S34Fixture.TEXT.split("\n")
        doc.references.forEach { r ->
            assertTrue(lines[r.sourceLine - 1].contains(
                if (r.type == S34RefType.BIBLE) ":" else r.rawText.take(10)))
        }
    }

    // ---------- Invariantes (§21) ----------

    @Test
    fun invariante1OrdemUnicaECrescente() {
        val orders = doc.sections.map { it.order }
        assertEquals(orders.sorted(), orders)
        assertEquals(orders.toSet().size, orders.size)
        doc.sections.forEach { s ->
            val sub = s.subsections.map { it.order }
            assertEquals(sub.sorted(), sub)
            assertEquals(sub.toSet().size, sub.size)
        }
    }

    @Test
    fun invariante2e5ReferenciasBatEmComScanIndependente() {
        // Acordo por linha: cada ref estruturada é reencontrada escaneando
        // SÓ a sua linha de origem (mesma granularidade do parser — o
        // detect() é sensível ao contexto das janelas-guia, então scan
        // integral vs. scan por linha podem divergir legitimamente).
        val lines = S34Fixture.TEXT.split("\n")
        assertEquals(
            listOf("João|17|17", "Tiago|2|17", "Hebreus|10|23"),
            doc.references.filter { it.type == S34RefType.BIBLE }
                .map { it.normalizedReference }
        )
        doc.references.forEach { r ->
            val line = lines[r.sourceLine - 1]
            when (r.type) {
                S34RefType.BIBLE -> assertTrue(
                    "sumiu: ${r.normalizedReference}",
                    RefDetector.detectBible(line).any {
                        "${it.label}|${it.chapter}|${it.verse}" == r.normalizedReference
                    }
                )
                S34RefType.PUBLICATION -> assertTrue(
                    "sumiu: ${r.rawText}",
                    RefDetector.detect(line).any { it.raw == r.rawText }
                )
            }
        }
        // Contenção estrutural: toda ref mora dentro do span da sua seção
        // (entre seu cabeçalho e o próximo) — nunca flutua sem dono.
        val spans = doc.sections.mapIndexed { i, s ->
            val end = doc.sections.getOrNull(i + 1)?.sourceLine ?: Int.MAX_VALUE
            s.id to (s.sourceLine until end)
        }.toMap()
        fun ownerOf(r: com.bettertalker.app.data.s34.S34Reference): String =
            doc.sections.first { s ->
                s.references.contains(r) || s.subsections.any { it.references.contains(r) }
            }.id
        doc.references.forEach { r ->
            assertTrue(
                "ref fora do span: ${r.rawText}",
                r.sourceLine in spans.getValue(ownerOf(r))
            )
        }
        // Limitação documentada: refs fora de seção (ex.: a pub que o
        // RefDetector enxerga no bloco de objetivo em scan integral) não
        // são estruturadas nesta fase — nenhuma ref aponta para antes da
        // primeira seção.
        val firstSecLine = doc.sections.minOf { it.sourceLine }
        assertTrue(doc.references.all { it.sourceLine >= firstSecLine })
    }

    @Test
    fun invariante3OrdemDentroDaSecaoEDocumental() {
        doc.sections.forEach { s ->
            val all = (s.references + s.subsections.flatMap { it.references })
            val byLine = all.map { it.sourceLine }
            assertEquals(byLine.sorted(), byLine)
        }
    }

    @Test
    fun invariante4NadaInventadoRoundTrip() {
        val t = S34Fixture.TEXT
        assertTrue(t.contains(doc.title))
        doc.objective?.let { assertTrue(t.contains(it)) }
        doc.sections.forEach { s ->
            assertTrue(t.contains(s.title))
            s.content.split("\n").filter { it.isNotBlank() }
                .forEach { assertTrue("sumiu: $it", t.contains(it)) }
            s.subsections.forEach { sub ->
                assertTrue(t.contains(sub.content))
            }
        }
        doc.references.forEach { r ->
            assertTrue("ref fora do texto: ${r.rawText}", t.contains(r.rawText))
        }
        doc.headerLines.forEach { assertTrue(t.contains(it)) }
    }

    // ---------- T1 (Bug #12): formato real (seções temporizadas sem número) ----------

    private val timed = S34Parser.parseS34(S34Fixture.TIMED_TITLES)

    @Test
    fun formatoReal5SecoesComMinutos() {
        assertEquals(5, timed.sections.size)
        assertEquals(listOf(3, 5, 5, 15, 2), timed.sections.map { it.minutes })
    }

    @Test
    fun formatoRealTitulosLimpos() {
        assertEquals("A paciência se prova nas pequenas escolhas", timed.sections[0].title)
        assertEquals("A paciência ajuda nos estudos", timed.sections[1].title)
        assertEquals("Continue cultivando paciência", timed.sections[4].title)
    }

    @Test
    fun formatoRealTemaEObjetivo() {
        assertEquals("Como cultivar paciência", timed.title)
        assertEquals(
            "Mostrar por que a paciência ajuda nas decisões do dia a dia.",
            timed.objective
        )
    }

    @Test
    fun formatoRealSubpontosEReferencias() {
        assertEquals(2, timed.sections[1].subsections.size)
        assertTrue(timed.sections.flatMap { it.references }.isNotEmpty())
        assertTrue(
            timed.sections.flatMap { s -> s.subsections.flatMap { it.references } }.isNotEmpty()
        )
    }

    @Test
    fun formatoRealCorpoNaoViraSecao() {
        assertEquals(
            "Ouvir com calma antes de opinar fortalece os vínculos.",
            timed.sections[2].content.trim()
        )
    }

    @Test
    fun formatoRealComNbspNosMinutos() {
        // Extração real (RTF/DOCX) traz NBSP: "(5\u00A0min)" — \s do Java não cobre.
        val nbsp = S34Parser.parseS34(
            S34Fixture.TIMED_TITLES.replace("(5 min)", "(5\u00A0min)")
        )
        assertEquals(5, nbsp.sections.size)
        assertEquals(listOf(3, 5, 5, 15, 2), nbsp.sections.map { it.minutes })
    }
}
