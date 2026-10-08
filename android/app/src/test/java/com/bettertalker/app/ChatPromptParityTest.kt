package com.bettertalker.app

import com.bettertalker.app.data.copilot.ChatTurn
import com.bettertalker.app.data.copilot.INSUFFICIENT_EVIDENCE_MESSAGE
import com.bettertalker.app.data.copilot.MAX_CONTENT_SOURCES
import com.bettertalker.app.data.copilot.MAX_TOTAL_SOURCES
import com.bettertalker.app.data.copilot.MAX_TRAINING_SOURCES
import com.bettertalker.app.data.copilot.SYSTEM_PROMPT
import com.bettertalker.app.data.copilot.blockSection
import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.copilot.focusLine
import com.bettertalker.app.data.copilot.packFor
import com.bettertalker.app.data.copilot.serializePack
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.repo.ScopedHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 16 — o prompt nativo precisa obedecer às mesmas regras de fidelidade do
 * web. Estes testes existem para impedir que alguém "simplifique" o prompt e
 * perca CONTENT/TRAINING, anti-atribuição ou insuficiência.
 */
class ChatPromptParityTest {

    private fun contentHit(id: String, text: String, ref: String = "") = ScopedHit(
        PassageEntity(
            id = id, attachmentId = "att", text = text, normalized = text,
            section = "", ref = ref, page = null, paragraph = null, ord = 0
        ),
        source = "Publicação"
    )

    private fun trainingHit(id: String, text: String, cat: TrainingCategory) = ScopedHit(
        PassageEntity(
            id = id, attachmentId = "be", text = text, normalized = text,
            section = "", ref = "", page = null, paragraph = null, ord = 0,
            trainingCategory = cat.serial
        ),
        source = "Beneficie-se"
    )

    private fun ev(
        id: String, reference: String, text: String,
        type: SourceType = SourceType.CONTENT, cat: TrainingCategory = TrainingCategory.UNKNOWN
    ) = EvidenceSource(
        id = id, reference = reference, text = text, sourceType = type,
        publication = "Pub", section = null, paragraph = null, page = null,
        trainingCategory = cat
    )

    // ---------- 1. regras de fidelidade presentes ----------

    @Test
    fun systemPromptTemAsRegrasDeFidelidade() {
        assertTrue(SYSTEM_PROMPT.contains("CRIATIVIDADE NA FORMA, FIDELIDADE NO CONTEÚDO"))
        // Anti-atribuição
        assertTrue(SYSTEM_PROMPT.contains("NUNCA invente"))
        // CONTENT/TRAINING nunca misturados
        assertTrue(SYSTEM_PROMPT.contains("nunca as use como fonte de fatos"))
        // Insuficiência com a frase exata
        assertTrue(SYSTEM_PROMPT.contains(INSUFFICIENT_EVIDENCE_MESSAGE))
        // Criação é sugestão do modelo, não da fonte
        assertTrue(SYSTEM_PROMPT.contains("nunca atribuídas à fonte"))
    }

    // ---------- 2. CONTENT e TRAINING em blocos separados ----------

    @Test
    fun trilhasAparecemEmSecoesDistintas() {
        val pack = ContextPack(
            contentSources = listOf(ev("c1", "A Sentinela", "texto do conteudo")),
            trainingSources = listOf(
                ev("t1", "Beneficie-se", "orientacao", SourceType.TRAINING, TrainingCategory.INTRODUCTION)
            )
        )
        val out = serializePack(pack)
        assertTrue(out.contains("FONTES DE CONTEÚDO"))
        assertTrue(out.contains("ORIENTAÇÕES DE ORATÓRIA"))
        // O trecho de orientação NÃO pode estar dentro do bloco de conteúdo.
        val contentBlock = out.substringAfter("FONTES DE CONTEÚDO").substringBefore("FIM DAS FONTES")
        assertFalse(contentBlock.contains("orientacao"))
    }

    @Test
    fun trainingSectionAvisaQueNaoEProva() {
        val pack = ContextPack(
            contentSources = emptyList(),
            trainingSources = listOf(
                ev("t1", "Beneficie-se", "orientacao", SourceType.TRAINING, TrainingCategory.DELIVERY)
            )
        )
        val out = serializePack(pack)
        assertTrue(out.contains("NÃO usar como fatos"))
        assertTrue(out.contains("técnica: delivery"))
    }

    @Test
    fun trainingNuncaEntraNoBucketDeConteudo() {
        // Reforço por construção: mesmo se o chamador misturar as listas, o
        // bucket de conteúdo fica sem nenhum trecho marcado como orientação.
        val pack = packFor(
            contentHits = listOf(
                trainingHit("t1", "so orientacao", TrainingCategory.DELIVERY),
                contentHit("c1", "materia real")
            ),
            trainingHits = listOf(trainingHit("t2", "outra", TrainingCategory.CLARITY))
        )
        assertEquals(1, pack.contentSources.size)
        assertEquals("materia real", pack.contentSources[0].text)
        assertEquals(1, pack.trainingSources.size)
    }

    @Test
    fun semFontesDizQueNaoTemSuporte() {
        val out = serializePack(ContextPack(emptyList(), emptyList()))
        assertTrue(out.contains("Nenhuma fonte do acervo local"))
        assertTrue(out.contains("não há suporte suficiente"))
    }

    // ---------- 3. limites de contexto ----------

    @Test
    fun limitesDeFontesSaoRespeitados() {
        val pack = ContextPack(
            contentSources = (1..20).map { ev("c$it", "Fonte $it", "texto $it") },
            trainingSources = (1..20).map {
                ev("t$it", "Treino $it", "texto $it", SourceType.TRAINING, TrainingCategory.CLARITY)
            }
        )
        val out = serializePack(pack)
        assertTrue(pack.contentSources.size == 20) // o pack guarda tudo...
        // ...mas o prompt corta nos limites da F15.
        assertEquals(MAX_CONTENT_SOURCES, out.lines().count { it.startsWith("[Fonte ") })
        assertEquals(MAX_TRAINING_SOURCES, out.lines().count { it.startsWith("[Orientação ") })
    }

    @Test
    fun orcamentoTotalDeFontesRespeita12() {
        val pack = ContextPack(
            contentSources = (1..8).map { ev("c$it", "C$it", "x") },
            trainingSources = (1..4).map { ev("t$it", "T$it", "x", SourceType.TRAINING, TrainingCategory.CLARITY) }
        )
        val out = serializePack(pack, legacyPassages = (1..30).map { "legado $it" })
        val total = out.lines().count {
            it.startsWith("[Fonte ") || it.startsWith("[Orientação ")
        }
        assertTrue("orçamento total = $total", total <= MAX_TOTAL_SOURCES)
    }

    @Test
    fun blocoEntraNoPromptComTituloEDuracao() {
        val sec = blockSection("Introdução", 5)
        assertTrue(sec.contains("BLOCO ATUAL"))
        assertTrue(sec.contains("Introdução"))
        assertTrue(sec.contains("5 minutos"))
        assertEquals("", blockSection(null, null))
    }

    // ---------- 4. foco: verificação rebaixa o treinamento ----------

    @Test
    fun focoDeVerificacaoProibeTreinoComoProva() {
        val f = focusLine("isso está certo?")
        assertTrue(f.contains("conferir"))
        assertTrue(f.contains("Não use orientações de oratória como prova factual"))
    }

    @Test
    fun focoNormalSeparaComoEOQue() {
        val f = focusLine("deixe mais natural")
        assertTrue(f.contains("COMO apresentar"))
        assertTrue(f.contains("O QUÊ"))
    }

    // ---------- 5. o prompt final do chat ----------

    @Test
    fun promptDoChatTemMensagemContextoELocalizacao() {
        val p = buildChatPrompt(
            message = "quero melhorar isso",
            history = listOf(ChatTurn(true, "a")),
            isFirstMessage = false,
            pack = ContextPack(listOf(ev("c1", "Fonte", "texto")), emptyList()),
            blockTitle = "Ponto 1", blockMinutes = 3, blockText = "corpo do bloco"
        )
        assertTrue(p.contains("Mensagem do usuário: \"quero melhorar isso\""))
        assertTrue(p.contains("CONVERSA ANTERIOR"))
        assertTrue(p.contains("Ponto 1"))
        assertTrue(p.contains("corpo do bloco"))
    }

    @Test
    fun promptProibeRotulosInternos() {
        val p = buildChatPrompt(
            message = "melhore", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null, blockMinutes = null, blockText = "x"
        )
        // A instrução anti-rótulo precisa estar lá, senão o modelo expõe o prompt.
        assertTrue(p.contains("sem rótulos internos"))
        assertTrue(p.contains("ANÁLISE DE INTENÇÃO"))
        // O prompt do chat referencia "a frase de insuficiência"; o texto exato
        // mora no SYSTEM_PROMPT (mesma divisão do web).
        assertTrue(p.contains("frase de insuficiência"))
    }

    @Test
    fun primeiraMensagemNaoTemSecaoDeContinuidade() {
        val p = buildChatPrompt(
            message = "oi", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null, blockMinutes = null, blockText = "x"
        )
        assertFalse(p.contains("Continuidade:"))
        assertFalse(p.contains("CONVERSA ANTERIOR"))
    }
    // ---------- prompt de edit-proposal (Fase 18 §14) ----------

    @Test
    fun editProposalPromptTemGoalJsonEFoco() {
        val p = com.bettertalker.app.data.copilot.buildEditProposalPrompt(
            com.bettertalker.app.data.edit.EditProposalMode.IMPROVE,
            "texto do bloco", pack = ContextPack(emptyList(), emptyList()),
            brief = "deixe mais natural")
        assertTrue(p.contains("PRESERVANDO"))
        assertTrue(p.contains("```json"))
        assertTrue(p.contains("deixe mais natural"))
        assertTrue(p.contains("texto do bloco"))
        // Fidelidade não cai no modo proposta.
        assertTrue(p.contains("Nunca invente fatos"))
    }

    @Test
    fun editProposalPromptRewriteEInsert() {
        val rw = com.bettertalker.app.data.copilot.buildEditProposalPrompt(
            com.bettertalker.app.data.edit.EditProposalMode.REWRITE, "x")
        assertTrue(rw.contains("INTEGRALMENTE"))
        val ins = com.bettertalker.app.data.copilot.buildEditProposalPrompt(
            com.bettertalker.app.data.edit.EditProposalMode.INSERT, "x")
        assertTrue(ins.contains("APÓS o bloco"))
    }

    // ---------- T4 (acesso bíblico): regra dos TEXTOS BÍBLICOS ----------

    @Test
    fun promptDoChatOrientaUsarTextosBiblicos() {
        val p = buildChatPrompt(
            message = "o que diz Jer. 29:11?",
            history = emptyList(),
            isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null, blockMinutes = null, blockText = "x",
        )
        // Regra T4: versículo resolvido é a fonte; sem bloco, sem suporte.
        assertTrue(p.contains("referência bíblica"))
        assertTrue(p.contains("## TEXTOS BÍBLICOS") || p.contains("bloco TEXTOS BÍBLICOS"))
        assertTrue(p.contains("diga que não há suporte"))
        // Regras de fidelidade existentes permanecem.
        assertTrue(p.contains("sem rótulos internos"))
        assertTrue(p.contains("frase de insuficiência"))
    }
}
