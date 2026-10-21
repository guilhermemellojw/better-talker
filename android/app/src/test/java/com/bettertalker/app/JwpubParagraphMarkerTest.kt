package com.bettertalker.app

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.copilot.serializePack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.planning.RoomReferenceResolver
import com.bettertalker.app.data.util.JwpubExtractor
import com.bettertalker.app.data.util.splitWithSectionsAndParagraphs
import com.bettertalker.app.domain.planning.PublicationRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * exp/chat-quality (2026-10-21) — RAG de publicações com TEXTO LITERAL.
 *
 * Reproduz a estrutura real do JWPUB (w25.07, "Você aprendeu o segredo do
 * contentamento?"): o parágrafo do artigo é marcado por
 * `<span class="parNum" data-pnum="N">`, enquanto a PERGUNTA do esboço
 * (`<p class="qu"><strong>N.</strong>…`) é a que a regra antiga marcava.
 * Resultado antes do fix: "w25.07 22 §§ 8-9" resolvia para o TÍTULO e o
 * corpo do parágrafo ficava sem numeração (`paragraph = null`).
 */
class JwpubParagraphMarkerTest {

    /** HTML real do §8 da w25.07 (recorte fiel: pergunta + campo + corpo). */
    private val html8 = """
        <h2 class="du-color--coolGray-700" id="p15"><strong>CONTINUE CONCENTRADO E HUMILDE</strong></h2>
        <p id="p53" data-pid="53" class="qu"> <strong>8.</strong> Em que armadilha Baruque caiu?</p>
        <div class="gen-field" id="p54" data-pid="54">
        <label for="tt76" id="tt74" class="dc-screenReaderText">Sua resposta</label>
        <textarea id="tt76" class="du-color--textSubdued" name="textarea-11"></textarea>
        </div>
        <p id="p16" data-pid="16" data-rel-pid="[53]"><span class="parNum" data-pnum="8"><strong><sup>8</sup></strong></span> Baruque, o secretário do profeta Jeremias, tinha uma designação difícil: ajudar Jeremias a transmitir uma forte mensagem para uma nação ingrata. (<a href="jwpub://b/NWTR/24:45:3-24:45:5" data-bid="14-1" class="b">Jer. 45:3-5</a>) Era como se Jeová tivesse dito: "Fique satisfeito com a sua situação atual."</p>
        <p id="p55" data-pid="55" class="qu"> <strong>9.</strong> De acordo com <a href="jwpub://b/NWTR/46:4:6-46:4:7" data-bid="15-1" class="b">1 Coríntios 4:6, 7</a>, o que a humildade vai nos motivar a reconhecer?</p>
    """.trimIndent()

    @Test
    fun extractor_extrai_texto_real_do_paragrafo_nao_titulo() {
        val marked = JwpubExtractor.markParagraphs(html8)
        // O CORPO do §8 nasce marcado com §8 (o número real do parágrafo).
        val bodyLine = marked.lines().first { it.contains("Baruque, o secretário") }
        assertTrue("corpo sem marcador §: $bodyLine", bodyLine.trimStart().startsWith("§8 "))
        // E o número impresso não sobra solto (" 8 Baruque…").
        assertFalse("número duplicado no corpo", bodyLine.contains(" 8 Baruque"))
    }

    @Test
    fun extractor_mapeia_paragraphNumberLabel_nao_paragraphIndex() {
        // No banco interno, o §8 do artigo tem ParagraphIndex = 16 (o rótulo é 8).
        // O marcador do HTML (data-pnum) é o RÓTULO — o index não pode virar §16.
        val marked = JwpubExtractor.markParagraphs(html8)
        val chunks = splitWithSectionsAndParagraphs(marked)
        val body = chunks.first { it.text.contains("Baruque, o secretário") }
        assertEquals(8, body.paragraph)
    }

    @Test
    fun splitter_propagaParagrafoAoCorpoDoParagrafo() {
        val marked = JwpubExtractor.markParagraphs(html8)
        val chunks = splitWithSectionsAndParagraphs(marked)
        val body = chunks.first { it.text.contains("Baruque, o secretário") }
        assertEquals(8, body.paragraph)
        // O corpo NÃO é a pergunta do esboço.
        val pergunta = chunks.first { it.text.contains("armadilha") }
        assertTrue(pergunta.text.startsWith("Em que armadilha"))
    }

    @Test
    fun resolver_w25_07_22_8_9_retorna_texto_literal() = runBlocking {
        val attachmentId = "att-w25"
        val corpo = PassageEntity(
            id = "$attachmentId-p1",
            attachmentId = attachmentId,
            text = "Baruque, o secretário do profeta Jeremias, tinha uma designação difícil.",
            normalized = "baruque o secretario do profeta jeremias",
            section = "Sua resposta",
            ref = "w25.07 Sua resposta §605",
            page = 22,
            paragraph = 8,
            ord = 605,
        )
        val resolver = RoomReferenceResolver(
            passageDao = fakePassageDao(attachmentId, listOf(corpo)),
            attachmentDao = fakeAttachmentDao(
                AttachmentEntity(
                    id = attachmentId,
                    noteId = null,
                    fileName = "w_T_202507.jwpub",
                    kind = "jwpub",
                    sizeBytes = 4_015_925,
                    appPath = "/data/user/0/com.bettertalker.app/files/publicacoes/att-w25",
                    indexed = true,
                    addedAt = 0L,
                    status = "ready",
                )
            ),
        )
        val resolved = resolver.resolvePublication(
            PublicationRef(symbol = "w25.07", page = 22, paragraph = 8, paragraphEnd = 9)
        )
        val text = resolved.text
        assertNotNull(text)
        assertTrue(
            "texto literal do §8 esperado, veio: $text",
            text!!.contains("Baruque, o secretário do profeta Jeremias"),
        )
        assertTrue(resolved.canonicalRef!!.contains("pág. 22"))
        assertTrue(resolved.canonicalRef!!.contains("§8"))
    }

    @Test
    fun dossier_usa_texto_do_paragrafo_nao_indice_sequencial() {
        val source = EvidenceSource(
            id = "p605",
            reference = "w25.07 Sua resposta §605",
            text = "Baruque, o secretário do profeta Jeremias…",
            sourceType = SourceType.CONTENT,
            publication = "w25.07",
            section = "Sua resposta",
            paragraph = 8,
            page = 22,
        )
        val out = serializePack(
            ContextPack(contentSources = listOf(source), trainingSources = emptyList())
        )
        assertTrue(out.contains("pág. 22"))
        assertTrue(out.contains("§8"))
        assertFalse("o índice sequencial não pode aparecer", out.contains("§605"))
    }

    // ---------- fakes dos DAOs (proxy dinâmico: só o que o resolver usa) ----------

    private fun fakePassageDao(
        attachmentId: String,
        rows: List<PassageEntity>,
    ): PassageDao = Proxy.newProxyInstance(
        PassageDao::class.java.classLoader,
        arrayOf(PassageDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "byPageParagraph" -> rows.filter {
                it.attachmentId == attachmentId &&
                    it.page == args?.get(1) &&
                    it.paragraph != null &&
                    it.paragraph in (args[2] as Int)..(args[3] as Int)
            }
            "forAttachment", "forAttachments", "forAttachmentsLimited" -> rows
            else -> defaultFor(method.returnType)
        }
    } as PassageDao

    private fun fakeAttachmentDao(attachment: AttachmentEntity): AttachmentDao =
        Proxy.newProxyInstance(
            AttachmentDao::class.java.classLoader,
            arrayOf(AttachmentDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "all" -> listOf(attachment)
                else -> defaultFor(method.returnType)
            }
        } as AttachmentDao

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }
}
