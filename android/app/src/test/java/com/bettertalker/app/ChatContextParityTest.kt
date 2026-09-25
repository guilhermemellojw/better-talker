package com.bettertalker.app

import com.bettertalker.app.data.copilot.ChatTurn
import com.bettertalker.app.data.copilot.EvidenceTrack
import com.bettertalker.app.data.copilot.buildTurnContext
import com.bettertalker.app.data.copilot.evidenceMetaFrom
import com.bettertalker.app.data.copilot.glyph
import com.bettertalker.app.data.copilot.packFor
import com.bettertalker.app.data.copilot.provenanceReference
import com.bettertalker.app.data.copilot.provenanceSummary
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.repo.ScopedHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 16 — contexto, proveniência e montagem do turno.
 *
 * Cobre o caminho que liga "contexto automático" + "proveniência" + "intenção
 * invisível" no mesmo pipeline.
 */
class ChatContextParityTest {

    private fun contentHit(id: String, text: String, ref: String = "", section: String = "") = ScopedHit(
        PassageEntity(
            id = id, attachmentId = "att", text = text, normalized = text,
            section = section, ref = ref, page = null, paragraph = null, ord = 0
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

    // ---------- proveniência ----------

    @Test
    fun provenienciaUsaRefRealQuandoExiste() {
        val hit = contentHit("c1", "texto", ref = "lff p. 31 §4")
        assertEquals("lff p. 31 §4", provenanceReference(hit))
    }

    @Test
    fun provenienciaNaoInventaRef() {
        // Sem ref e sem seção: só a fonte, nada inventado.
        assertEquals("Publicação", provenanceReference(contentHit("c1", "t")))
        // Com seção, ela entra explicitamente.
        assertTrue(provenanceReference(contentHit("c1", "t", section = "Capítulo 5")).contains("Capítulo 5"))
    }

    @Test
    fun glifosSeparamConteudoETecnica() {
        assertEquals("📖", EvidenceTrack.CONTENT.glyph())
        assertEquals("🎤", EvidenceTrack.TRAINING.glyph())
    }

    @Test
    fun provenienciaClassificaPorCategoriaIndexada() {
        val meta = evidenceMetaFrom(
            listOf(
                contentHit("c1", "materia"),
                trainingHit("t1", "tecnica", TrainingCategory.NATURALNESS)
            )
        )
        assertEquals(EvidenceTrack.CONTENT, meta[0].track)
        assertEquals(EvidenceTrack.TRAINING, meta[1].track)
        assertEquals(TrainingCategory.NATURALNESS, meta[1].category)
    }

    @Test
    fun resumoContaPorTrilho() {
        val meta = evidenceMetaFrom(
            listOf(
                contentHit("c1", "a"), contentHit("c2", "b"),
                trainingHit("t1", "c", TrainingCategory.DELIVERY)
            )
        )
        assertEquals("2 conteúdo · 1 técnica", provenanceSummary(meta))
        assertEquals("0 conteúdo · 0 técnica", provenanceSummary(emptyList()))
    }

    // ---------- contexto automático ----------

    @Test
    fun contextoAutomaticoUsaBlocoSemExigirIdDoUsuario() {
        val ctx = buildTurnContext(
            message = "melhore isso",
            history = emptyList(), isFirstMessage = true,
            contentHits = listOf(contentHit("c1", "materia")),
            blockTitle = "Introdução", blockMinutes = 4, blockText = "texto do bloco"
        )
        assertTrue(ctx.prompt.contains("Introdução"))
        assertTrue(ctx.prompt.contains("4 minutos"))
        assertTrue(ctx.prompt.contains("texto do bloco"))
    }

    @Test
    fun contextoSemBlocoNaoInventaBloco() {
        val ctx = buildTurnContext(
            message = "quero saber mais", history = emptyList(), isFirstMessage = true,
            contentHits = listOf(contentHit("c1", "materia")),
            blockTitle = null, blockMinutes = null, blockText = "nota inteira"
        )
        assertFalse(ctx.prompt.contains("BLOCO ATUAL"))
        assertTrue(ctx.prompt.contains("nota inteira"))
    }

    // ---------- pipeline único ----------

    @Test
    fun quickActionGeraOMesmoPromptQueMensagemDigitada() {
        // §9: atalho NÃO pode ter prompt especial.
        val fromChip = buildTurnContext(
            message = "Deixe mais natural.", history = emptyList(), isFirstMessage = true,
            contentHits = listOf(contentHit("c1", "materia")),
            blockTitle = "P1", blockMinutes = 2, blockText = "corpo"
        )
        val typed = buildTurnContext(
            message = "Deixe mais natural.", history = emptyList(), isFirstMessage = true,
            contentHits = listOf(contentHit("c1", "materia")),
            blockTitle = "P1", blockMinutes = 2, blockText = "corpo"
        )
        assertEquals(typed.prompt, fromChip.prompt)
    }

    @Test
    fun turnoCarregaContinuidadeNoPrompt() {
        val ctx = buildTurnContext(
            message = "agora deixe mais natural",
            history = listOf(ChatTurn(true, "quero melhorar a intro"), ChatTurn(false, "posso mexer no gancho")),
            isFirstMessage = false,
            contentHits = listOf(contentHit("c1", "materia")),
            blockTitle = "Introdução", blockMinutes = null, blockText = "corpo"
        )
        assertTrue(ctx.prompt.contains("CONVERSA ANTERIOR"))
        assertTrue(ctx.prompt.contains("Continuidade:"))
        assertTrue(ctx.prompt.contains("quero melhorar a intro"))
    }

    @Test
    fun turnoComVerificacaoNAoRecebeGuia() {
        // Verificação factual: o chamador passa contentHits só; o prompt não pode
        // ganhar seção de oratória.
        val ctx = buildTurnContext(
            message = "isso está certo nas fontes?",
            history = emptyList(), isFirstMessage = true,
            contentHits = listOf(contentHit("c1", "materia")),
            trainingHits = emptyList(),
            blockTitle = null, blockMinutes = null, blockText = "x"
        )
        assertTrue(ctx.prompt.contains("Não use orientações de oratória como prova factual"))
        assertFalse(ctx.prompt.contains("ORIENTAÇÕES DE ORATÓRIA"))
    }

    @Test
    fun turnoDevolveProvenienciaProntaParaUI() {
        val ctx = buildTurnContext(
            message = "me dá um exemplo", history = emptyList(), isFirstMessage = true,
            contentHits = listOf(contentHit("c1", "materia", ref = "A Sentinela p. 5")),
            trainingHits = listOf(trainingHit("t1", "dica", TrainingCategory.ILLUSTRATION)),
            blockTitle = null, blockMinutes = null, blockText = "x"
        )
        assertEquals(2, ctx.evidence.size)
        assertEquals(EvidenceTrack.CONTENT, ctx.evidence[0].track)
        assertEquals(EvidenceTrack.TRAINING, ctx.evidence[1].track)
        assertEquals("A Sentinela p. 5", ctx.evidence[0].reference)
    }

    @Test
    fun packMarcaSourceTypeCorreto() {
        val pack = packFor(
            contentHits = listOf(contentHit("c1", "materia")),
            trainingHits = listOf(trainingHit("t1", "tecnica", TrainingCategory.CLARITY))
        )
        assertEquals(SourceType.CONTENT, pack.contentSources[0].sourceType)
        assertEquals(SourceType.TRAINING, pack.trainingSources[0].sourceType)
    }
}
