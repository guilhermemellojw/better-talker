package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.ai.checkCitations
import com.bettertalker.app.data.copilot.ChatTurnContext
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.repo.ScopedHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3 (acesso bíblico): versículos citados NA PERGUNTA ("o que diz Jer.
 * 29:11?") injetados no prompt do chat remoto — só quando citados (P4).
 */
class BibleQuestionPromptTest {

    private fun hit(ref: String, text: String) = ScopedHit(
        PassageEntity("p-$ref", "nwt", text, text, ref = ref),
        "Tradução do Novo Mundo da Bíblia Sagrada",
    )

    private fun turn() = ChatTurnContext(
        pack = ContextPack(emptyList(), emptyList()),
        prompt = "",
        evidence = emptyList(),
    )

    @Test
    fun questionBibleBlock_listaRefETextoLiteral() {
        val block = questionBibleBlock(listOf(hit("Je 29:11", "“Pois eu sei muito bem”")))

        assertTrue(block.startsWith("## TEXTOS BÍBLICOS (referências da pergunta)"))
        assertTrue(block.contains("- Je 29:11: \"“Pois eu sei muito bem”\""))
    }

    @Test
    fun questionBibleBlock_semVersiculos_naoInjeta() {
        assertEquals("", questionBibleBlock(emptyList()))
    }

    @Test
    fun combineContextBlocks_dossieMaisVersiculos() {
        val out = combineContextBlocks("## SEÇÃO ATUAL\nTítulo: X", listOf(hit("Je 29:11", "texto")))

        assertTrue(out!!.contains("## SEÇÃO ATUAL"))
        assertTrue(out.contains("## TEXTOS BÍBLICOS (referências da pergunta)"))
        // dossiê primeiro; versículos da pergunta depois
        assertTrue(out.indexOf("## SEÇÃO ATUAL") < out.indexOf("## TEXTOS BÍBLICOS"))
    }

    @Test
    fun combineContextBlocks_semNada_null() {
        assertNull(combineContextBlocks(null, emptyList()))
        assertNull(combineContextBlocks("   ", emptyList()))
    }

    @Test
    fun combineContextBlocks_semDossie_mantemSoBiblia() {
        val out = combineContextBlocks(null, listOf(hit("Je 29:11", "texto")))
        assertTrue(out!!.startsWith("## TEXTOS BÍBLICOS"))
    }

    @Test
    fun verificationCorpus_incluiVersiculoDaPergunta() {
        val verses = listOf(hit("Je 29:11", "“Pois eu sei muito bem”"))
        val corpus = verificationCorpus(turn(), questionVerseLines(verses))
        assertTrue(corpus.contains("[Je 29:11] “Pois eu sei muito bem”"))
    }

    @Test
    fun questionVerseLines_permitemCitarARefSemAvisoFalso() {
        // Regressão do device: a ref injetada precisa estar no corpus (senão o
        // pós-check acusa "fora dos trechos") — inclusive na forma do usuário
        // ("Jer. 29:11"), que o modelo repete.
        val verses = listOf(hit("Je 29:11", "“Pois eu sei muito bem o que tenho em mente para vocês”"))
        val lines = questionVerseLines(verses, "o que diz Jer. 29:11?")
        assertTrue(lines.first().startsWith("[Je 29:11] "))
        assertTrue(lines.last().contains("jer 29 11"))

        assertTrue(
            checkCitations(
                "Veja Je 29:11: “Pois eu sei muito bem o que tenho em mente para vocês”.",
                lines,
            ).ok
        )
        assertTrue(checkCitations("Veja Jer. 29:11.", lines).ok)

        // Sem a linha da ref (e sem o alias), o mesmo texto acusa.
        assertFalse(
            checkCitations(
                "Veja Jer. 29:11.",
                listOf("“Pois eu sei muito bem o que tenho em mente para vocês”"),
            ).ok
        )
    }
}
