package com.bettertalker.app.data.copilot

import com.bettertalker.app.ui.copilot.verifyChatText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TRUST-P0A — Fail-safe bíblico do chat (P0): reprodutos e contratos.
 *
 * Repro (vermelho antes do fix, verde depois):
 * - `verifyChatText("Em Mateus 27:26 disse...", corpusVazio).notice` — antes
 *   do fix: `null` (a citação bíblica não foi retornada do acervo, mas não
 *   há nenhuma marcação) — acende nenhuma restrição factual, então o texto
 *   pode ser considerado apoio factual. Depois do fix: notice aponta a
 *   bíblia sem retorno.
 *
 * Contratos (Cenários P2 do QA-FIX-43, nível "Deterministic Failure Safe"):
 */
class ChatBiblicalSupportTest {

    // ---------- Contrato P0: biblicalGaps (verificação do acervo) ----------

    @Test
    fun tnmAusente_citacaoBiblicaNaoEstaSemMarcacao() {
        val gaps = ChatBiblicalSupport.biblicalGaps("Em Mateus 27:26 disse", emptyList())
        assertTrue(gaps.isNotEmpty())
    }

    @Test
    fun tnmImportadaMasNaoIndexada_citacaoBiblicaNaoEstaSemMarcacao() {
        // corpus vazio: TNM importada sem leitura / fallback ausente — mesmo contrato.
        assertTrue(ChatBiblicalSupport.biblicalGaps("Em Mateus 27:26 disse", emptyList()).isNotEmpty())
    }

    @Test
    fun referenciaBiblicaDetectadaSemTextoRecuperado() {
        assertTrue(ChatBiblicalSupport.biblicalGaps("Disse Em Mt 27:26", emptyList()).isNotEmpty())
    }

    @Test
    fun textoDoUsuarioContendoUmVersiculo() {
        assertTrue(ChatBiblicalSupport.biblicalGaps("Citou o usuário Em Mateus 27:26.", emptyList()).isNotEmpty())
    }

    @Test
    fun historicoContendoCitacaoIncorreta() {
        assertTrue(ChatBiblicalSupport.biblicalGaps("Histórico: disse Em Mt 27:26", emptyList()).isNotEmpty())
    }

    @Test
    fun referenciaInexistenteCitadaNaResposta() {
        assertTrue(ChatBiblicalSupport.biblicalGaps("Disse Em Mt 28:99", emptyList()).isNotEmpty())
    }

    @Test
    fun pedagogicoSemCitacao_naoExisteGap() {
        // 11. Pedagógico: resposta sem citação — não pode gerar gap.
        assertEquals(0, ChatBiblicalSupport.biblicalGaps("A vida tem seus momentos de ensino.", emptyList()).size)
    }

    @Test
    fun outraBibliaDisponivelTnmAusente_complementoDocumentalSemGap() {
        // 5. Outra tradução bíblica disponível — a recuperação legítima é corpus.
        val corpus = listOf("Mateus 27:26 — disponível no acervo")
        assertEquals(0, ChatBiblicalSupport.biblicalGaps("Em Mateus 27:26 disse", corpus).size)
    }

    @Test
    fun textoRecuperadoNoCorpus_semGap() {
        // TNM pronta, citação retornada — fonte documental elegível.
        val corpus = listOf("Mateus 27:26 — trecho recuperado no acervo")
        assertEquals(0, ChatBiblicalSupport.biblicalGaps("Em Mateus 27:26 disse", corpus).size)
    }

    // ---------- Notice (Integração) ----------

    @Test
    fun noticeComIdentificaçãoDoVersiculo() {
        val gaps = ChatBiblicalSupport.biblicalGaps("Em Mateus 27:26 disse", emptyList())
        assertNotNull(gaps)
        assertTrue(gaps.isNotEmpty())
        assertEquals("Mateus 27:26", gaps[0].ref)
    }

    @Test
    fun noticeFicaVaziaSemGaps() {
        assertNull(ChatBiblicalSupport.notice(emptyList()))
    }

    @Test
    fun noticeDescreveCitacaoSemRetorno() {
        val gaps = ChatBiblicalSupport.biblicalGaps("Em Mateus 27:26 disse", emptyList())
        val notice = ChatBiblicalSupport.notice(gaps)
        assertNotNull(notice)
        assertTrue(notice!!.contains("bíblicas sem retorno do acervo local"))
    }

    @Test
    fun noticeWithBiblicalSafety_uneAvisos() {
        assertNull(ChatBiblicalSupport.noticeWithBiblicalSafety(null, "sem refs bíblicas", emptyList()))
        val notice = ChatBiblicalSupport.noticeWithBiblicalSafety("⚠️ Revise: x", "Em Mateus 27:26 disse", emptyList())
        assertNotNull(notice)
        assertTrue(notice!!.startsWith("⚠️ Revise: x"))
        assertTrue(notice.contains("bíblicas sem retorno do acervo local"))
        val noticeBible = ChatBiblicalSupport.noticeWithBiblicalSafety(null, "Em Mateus 27:26 disse", emptyList())
        assertNotNull(noticeBible)
    }

    @Test
    fun controle10_avisoPrevioNaoSuprimeAvisoBiblico() {
        // Parecer QA-TRUST-P0A-48: com aviso prévio o aviso bíblico era
        // suprimido (2 falhas em 3 sondas). Ambos devem ser exibidos.
        val answer = "Em Mateus 27:26, \"trecho sem apoio no acervo com mais de vinte caracteres\"."
        val corpus = listOf("Texto de outra fonte sem relação com a citação")
        val outcome = verifyChatText(answer, corpus)
        assertNotNull(outcome.notice)
        assertTrue(outcome.notice!!.contains("⚠️ Revise"))
        assertTrue(outcome.notice!!.contains("bíblicas sem retorno do acervo local"))
        assertTrue(outcome.notice!!.contains("Mateus 27:26"))
    }

    // ---------- Repro P0 (fails BEFORE the integration, passes after) ----------

    @Test
    fun repro_verifyChatTextSemCorpusBíblicoNãoProduzAvisoBíblico() {
        val outcome = verifyChatText("Em Mateus 27:26 disse", emptyList())
        assertNotNull(outcome.notice)
        assertTrue(outcome.notice!!.contains("bíblicas sem retorno do acervo local"))
        assertTrue(outcome.notice!!.contains("Mateus 27:26"))
    }

    @Test
    fun repro_verifyChatTextComCorpusCorretoNãoProduzAvisoBíblico() {
        // Recuperado — nenhum gap — notice quiet (carrega outras flags se houver; vazio aqui).
        val corpus = listOf("Mateus 27:26 — trecho recuperado no acervo")
        val outcome = verifyChatText("Em Mateus 27:26 disse", corpus)
        if (outcome.notice != null) {
            assertFalse(outcome.notice!!.contains("bíblicas sem retorno do acervo local"))
        }
    }
}
