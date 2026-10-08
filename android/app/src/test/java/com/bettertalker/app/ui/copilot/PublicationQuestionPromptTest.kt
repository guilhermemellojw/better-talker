package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatTurnContext
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.repo.ScopedHit
import com.bettertalker.app.data.repo.isGuidePubRef
import com.bettertalker.app.data.repo.publicationHitOf
import com.bettertalker.app.data.repo.publicationRefOf
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.planning.ResolvedReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 (expert em publicações): trechos citados NA PERGUNTA injetados no
 * prompt — espelho do caminho bíblico. `be`/`th` ficam de fora (P4).
 */
class PublicationQuestionPromptTest {

    private fun detected(
        raw: String,
        pubKey: String,
        article: String? = null,
        chapter: String? = null,
    ) = RefDetector.DetectedRef(
        raw = raw,
        kind = RefDetector.Kind.BOOK,
        pubKey = pubKey,
        editionKey = "book|$pubKey",
        label = raw,
        article = article,
        chapter = chapter,
    )

    private fun hit(ref: String, text: String) = ScopedHit(
        PassageEntity("pub-$ref", "", text, text, ref = ref),
        "Seja Feliz Para Sempre",
    )

    private fun turn() = ChatTurnContext(
        pack = ContextPack(emptyList(), emptyList()),
        prompt = "",
        evidence = emptyList(),
    )

    @Test
    fun questionPublicationBlock_listaRefETextoLiteral() {
        val block = questionPublicationBlock(listOf(hit("lff cap. 5", "texto do capítulo")))

        assertTrue(block.startsWith("## TRECHOS DE PUBLICAÇÕES (referências da pergunta)"))
        assertTrue(block.contains("- lff cap. 5: \"texto do capítulo\""))
    }

    @Test
    fun questionPublicationBlock_semTrechos_naoInjeta() {
        assertEquals("", questionPublicationBlock(emptyList()))
    }

    @Test
    fun isGuidePubRef_beThFora_lffDentro() {
        assertTrue(isGuidePubRef(detected("be pág. 52", "be")))
        assertTrue(isGuidePubRef(detected("th lição 3", "th")))
        assertFalse(isGuidePubRef(detected("lff cap. 5", "lff")))
        assertFalse(isGuidePubRef(detected("it “Gedalias” n.° 4", "it")))
    }

    @Test
    fun publicationRefOf_mapeiaUnidadeCitada() {
        val ref = publicationRefOf(detected("lff cap. 5", "lff", chapter = "cap 5"))

        assertEquals(PublicationRef(symbol = "lff cap. 5", chapter = "cap 5"), ref)
    }

    @Test
    fun publicationHitOf_resolvedViraHit_unresolvedNulo() {
        val ok = publicationHitOf(
            ResolvedReference("lff cap. 5", "lff cap. 5", "texto real", "p1", ReferenceStatus.RESOLVED),
            "Seja Feliz",
        )
        assertEquals("lff cap. 5", ok?.passage?.ref)
        assertEquals("texto real", ok?.passage?.text)

        val partial = publicationHitOf(
            ResolvedReference("it", "it “Gedalias” §4", "texto aprox.", "p2", ReferenceStatus.PARTIAL),
            "Perspicaz",
        )
        assertEquals("it “Gedalias” §4", partial?.passage?.ref)

        assertNull(
            publicationHitOf(
                ResolvedReference("lff", null, null, null, ReferenceStatus.UNRESOLVED),
                "Seja Feliz",
            )
        )
        assertNull(
            publicationHitOf(
                ResolvedReference("lff", "lff", "   ", "p3", ReferenceStatus.RESOLVED),
                "Seja Feliz",
            )
        )
    }

    @Test
    fun combineContextBlocks_ordemDossieBibliaPublicacao() {
        val out = combineContextBlocks(
            "## SEÇÃO ATUAL\nTítulo: X",
            listOf(
                ScopedHit(
                    PassageEntity("p1", "nwt", "texto", "texto", ref = "Je 29:11"),
                    "TNM",
                )
            ),
            listOf(hit("lff cap. 5", "texto pub")),
        )

        assertTrue(out!!.contains("## SEÇÃO ATUAL"))
        assertTrue(out.contains("## TEXTOS BÍBLICOS (referências da pergunta)"))
        assertTrue(out.contains("## TRECHOS DE PUBLICAÇÕES (referências da pergunta)"))
        assertTrue(out.indexOf("## SEÇÃO ATUAL") < out.indexOf("## TEXTOS BÍBLICOS"))
        assertTrue(out.indexOf("## TEXTOS BÍBLICOS") < out.indexOf("## TRECHOS DE PUBLICAÇÕES"))
    }

    @Test
    fun verificationCorpus_incluiTrechoDaPublicacao() {
        val corpus = verificationCorpus(turn(), listOf("texto do capítulo sobre o perdão"))
        assertTrue(corpus.contains("texto do capítulo sobre o perdão"))
    }
}
