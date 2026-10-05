package com.bettertalker.app

import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.S34Detector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 19-B.1 — testes do detector S-34 (§11) + teste da fixture (§12).
 * Determinísticos, sem IO, sem rede.
 */
class S34DetectorTest {

    // ---------- Positivos (§11.1-2) ----------

    @Test
    fun fixtureS34EReconhecida() {
        assertTrue(S34Detector.isS34(S34Fixture.TEXT))
    }

    @Test
    fun marcadorToleraVariacoes() {
        val lower = S34Fixture.TEXT.replace("S-34", "s-34")
        assertTrue(S34Detector.isS34(lower))
        val glued = S34Fixture.TEXT.replace("S-34", "S34")
        assertTrue(S34Detector.isS34(glued))
    }

    // ---------- T1 (Bug #9): filename como sinal ----------

    @Test
    fun filenameS34DesbloqueiaDeteccaoSemLiteralNoTexto() {
        val semMarcador = S34Fixture.TEXT.replace(
            "S-34 — TEXTO SINTÉTICO PARA TESTE (não é um esboço oficial)", "ESBOÇO PARA TESTE"
        )
        assertFalse(S34Detector.isS34(semMarcador))
        assertTrue(S34Detector.isS34(semMarcador, "s34-35.docx"))
        assertTrue(S34Detector.isS34(semMarcador, "S-34_T_035.jwpub"))
    }

    @Test
    fun filenameComumNaoDesbloqueia() {
        val semMarcador = S34Fixture.TEXT.replace(
            "S-34 — TEXTO SINTÉTICO PARA TESTE (não é um esboço oficial)", "ESBOÇO PARA TESTE"
        )
        assertFalse(S34Detector.isS34(semMarcador, "discurso.docx"))
        assertFalse(S34Detector.isS34(semMarcador, null))
    }

    // ---------- Negativos (§11.3-7) ----------

    @Test
    fun documentoGenericoNaoES34() {
        val text = "Ata da reunião de condomínio do dia 12.\n" +
            "Presentes: síndico, zelador e três moradores.\n" +
            "Pauta: reforma da garagem e pintura da fachada.\n".repeat(4)
        assertFalse(S34Detector.isS34(text))
    }

    @Test
    fun anotacaoPessoalComPontosNaoES34() {
        // Pontos numerados SEM marcador: o portão do marcador decide.
        val text = "Minhas anotações da semana.\n" +
            "1. comprar pão e leite no mercado\n" +
            "2. ligar para o banco sobre a fatura\n" +
            "3. buscar as crianças na escola\n".repeat(4)
        assertFalse(S34Detector.isS34(text))
    }

    @Test
    fun publicacaoComumNaoES34() {
        // Refs reais (w-code + versículo) mas SEM marcador S-34.
        val text = "A Sentinela de estudo nos lembra de orar sem cessar. " +
            "Ver w24.03 e meditar em João 3:16 todos os dias. ".repeat(6)
        assertFalse(S34Detector.isS34(text))
    }

    @Test
    fun textoBiblicoIsoladoNaoES34() {
        val text = "João 3:16 Porque Deus amou tanto o mundo que deu " +
            "o seu Filho unigênito. João 1:1 No princípio era a Palavra."
        assertFalse(S34Detector.isS34(text))
    }

    @Test
    fun documentoBeThNaoES34() {
        // Conteúdo de treinamento com lição, SEM marcador S-34.
        val text = ("Beneficie-se da Escola do Ministério Teocrático. " +
            "Lição 5: leia com entusiasmo e contato visual. ").repeat(6)
        assertFalse(S34Detector.isS34(text))
    }

    // ---------- Robustez (§11.8-10) ----------

    @Test
    fun documentoVazioNaoES34() {
        assertFalse(S34Detector.isS34(""))
        assertFalse(S34Detector.isS34("   \n  "))
    }

    @Test
    fun documentoMuitoCurtoNaoES34() {
        assertFalse(S34Detector.isS34("S-34"))
        assertFalse(S34Detector.isS34("S-34: fé"))
    }

    @Test
    fun refsBiblicasSemEstruturaNaoBastam() {
        // Marcador + versículos, mas SEM pontos/tempos/objetivo/publicação:
        // 1 sinal só (< 2) → falso. Comprimento acima do piso p/ testar a regra.
        val text = "S-34\n" + "Leia a Bíblia com atenção todos os dias. ".repeat(8) +
            "\nLeia João 17:17. Leia Tiago 2:17."
        assertTrue(text.length >= S34Detector.MIN_S34_CHARS)
        assertTrue(S34Detector.hasMarker(text))
        assertFalse(S34Detector.isS34(text))
    }

    // ---------- Fixture (§12) ----------

    @Test
    fun fixtureTemSinaisParaF19B2() {
        val t = S34Fixture.TEXT
        assertTrue("identifica S-34", S34Detector.isS34(t))
        assertTrue("contém objetivo", t.contains("Objetivo:"))
        val points = Regex("""(?m)^\s*\d[.)]\s+\S""").findAll(t).toList()
        val starts = Regex("""(?m)^\s*\d[.)]\s+\S""").findAll(t).map { it.range.first }.toList()
        assertEquals("contém 3 pontos", 3, points.size)
        assertEquals("ordem textual consistente", starts.sorted(), starts)
        val subideas = Regex("""(?m)^\s*[a-z]\)""").findAll(t).toList()
        assertTrue("contém subideias", subideas.size >= 2)
        assertTrue("contém 3 refs bíblicas",
            RefDetector.detectBible(t).size >= 3)
        assertTrue("contém 2 refs de publicação",
            RefDetector.detect(t).size >= 2)
        assertTrue("preserva ordem textual", starts[0] < starts[1] && starts[1] < starts[2])
        assertFalse("sem INTRODUÇÃO artificial", t.contains("introdução", ignoreCase = true))
        assertFalse("sem CONCLUSÃO artificial", t.contains("conclusão", ignoreCase = true))
    }
}
