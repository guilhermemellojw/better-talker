package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.buildDeepSeekPrompts
import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.llm.GroundednessVerifier
import com.bettertalker.app.data.planning.RoomReferenceResolver
import com.bettertalker.app.data.repo.isGuidePubRef
import com.bettertalker.app.data.repo.publicationHitOf
import com.bettertalker.app.data.repo.publicationRefOf
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.ReferenceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3 (expert em publicações) — fluxo ponta a ponta travando T1–T2, espelho
 * do [BibleAccessFlowTest]: pergunta → `resolvePublication` real (com fakes
 * in-memory) → bloco no prompt real do DeepSeek → gate preserva a citação
 * injetada; `be`/`th` nunca viram conteúdo.
 */
class PublicationAccessFlowTest {

    private class FakePassageDao(var rows: List<PassageEntity> = emptyList()) : PassageDao {
        override suspend fun insertAll(items: List<PassageEntity>) { rows = rows + items }
        override suspend fun forAttachment(attachmentId: String): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId }
        override suspend fun deleteForAttachment(attachmentId: String) {
            rows = rows.filter { it.attachmentId != attachmentId }
        }
        override suspend fun searchLike(norm: String, limit: Int): List<PassageEntity> =
            rows.filter { it.normalized.contains(norm) }.take(limit)
        override suspend fun searchLikeIn(ids: List<String>, norm: String, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId in ids && it.normalized.contains(norm) }.take(limit)
        override suspend fun indexedAttachmentIds(): List<String> =
            rows.map { it.attachmentId }.distinct()
        override suspend fun findByRef(ref: String): PassageEntity? =
            rows.firstOrNull { it.ref == ref }
        override suspend fun forAttachments(ids: List<String>): List<PassageEntity> =
            rows.filter { it.attachmentId in ids }.sortedWith(compareBy({ it.attachmentId }, { it.ord }))
        override suspend fun forAttachmentsLimited(ids: List<String>, limit: Int): List<PassageEntity> =
            forAttachments(ids).take(limit)
        override suspend fun betweenOrd(attachmentId: String, from: Int, to: Int, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId && it.ord in from..to }
                .sortedBy { it.ord }.take(limit)
        override suspend fun nextTitleAfter(attachmentId: String, ord: Int): PassageEntity? =
            rows.filter { it.attachmentId == attachmentId && it.text.startsWith("# ") && it.ord > ord }
                .minByOrNull { it.ord }
        override suspend fun bySection(attachmentId: String, needle: String, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId && it.section.contains(needle, ignoreCase = true) }
                .sortedBy { it.ord }.take(limit)
    }

    private class FakeAttachmentDao(var rows: List<AttachmentEntity> = emptyList()) : AttachmentDao {
        override fun observe(): Flow<List<AttachmentEntity>> = MutableStateFlow(rows)
        override fun observeForNote(noteId: String): Flow<List<AttachmentEntity>> = MutableStateFlow(rows)
        override suspend fun all(): List<AttachmentEntity> = rows
        override suspend fun get(id: String): AttachmentEntity? = rows.firstOrNull { it.id == id }
        override suspend fun upsert(a: AttachmentEntity) { rows = rows.filter { it.id != a.id } + a }
        override suspend fun update(a: AttachmentEntity) { rows = rows.filter { it.id != a.id } + a }
        override suspend fun setIndexed(id: String, indexed: Boolean) {}
        override suspend fun setStatus(id: String, indexed: Boolean, status: String, error: String?) {}
        override suspend fun setBaseSlot(id: String, slot: String?) {}
        override suspend fun setNote(id: String, noteId: String?) {}
        override suspend fun baseReady(slot: String): AttachmentEntity? = null
        override suspend fun delete(id: String) { rows = rows.filter { it.id != id } }
        override suspend fun setSourceMeta(id: String, sourceType: String, symbol: String?) {}
    }

    private fun lffAttachment() = AttachmentEntity(
        "att-lff", null, "lff_T.epub", "epub", 1L, "/x", true, 1L,
        baseSlot = "lff", symbol = "lff",
    )

    private fun lffCap5() = PassageEntity(
        "p-lff-5", "att-lff",
        "O perdão é uma das maiores provas de amor que podemos dar",
        "o perdao e uma das maiores provas de amor que podemos dar",
        section = "Capítulo 5",
    )

    private fun resolver() = RoomReferenceResolver(
        FakePassageDao(listOf(lffCap5())),
        FakeAttachmentDao(listOf(lffAttachment())),
    )

    @Test
    fun fluxoPergunta_detectaLffCap5NaPergunta() {
        val refs = RefDetector.detect("o que lff cap. 5 diz sobre o perdao?")

        assertTrue(refs.any { it.pubKey == "lff" && it.chapter == "cap. 5" })
        assertTrue(refs.none { isGuidePubRef(it) })
    }

    @Test
    fun fluxoPergunta_trechoLffNoPromptDoChat() = runBlocking {
        val detected = RefDetector.detect("o que lff cap. 5 diz sobre o perdao?")
            .first { it.pubKey == "lff" }
        val resolved = resolver().resolvePublication(publicationRefOf(detected))

        // O resolvedor real acha a unidade (Capítulo 5) no acervo fake.
        assertTrue(
            "status era ${resolved.status}",
            resolved.status == ReferenceStatus.RESOLVED || resolved.status == ReferenceStatus.PARTIAL,
        )
        val hit = publicationHitOf(resolved, resolved.canonicalRef ?: detected.label)!!
        val block = questionPublicationBlock(listOf(hit))
        val combined = combineContextBlocks("## SEÇÃO ATUAL\nTítulo: X", emptyList(), listOf(hit))

        // O prompt REAL do DeepSeek carrega o bloco (system).
        val prompts = buildDeepSeekPrompts(
            message = "o que lff cap. 5 diz sobre o perdao?",
            history = emptyList(),
            isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null,
            blockMinutes = null,
            blockText = "",
            contextBlock = combined,
        )
        assertTrue(prompts.system.contains("## TRECHOS DE PUBLICAÇÕES (referências da pergunta)"))
        assertTrue(prompts.system.contains("O perdão é uma das maiores provas de amor"))
        assertTrue(block.contains("## TRECHOS DE PUBLICAÇÕES"))
    }

    @Test
    fun fluxoBeTh_saoGuias_nuncaConteudo() {
        // P4: mesmo citadas na pergunta, be/th não geram bloco de conteúdo.
        val refs = RefDetector.detect("o que be pag. 52 diz sobre ilustracoes?")
        val guias = refs.filter { isGuidePubRef(it) }

        assertTrue("be devia ser detectado como guia", guias.any { it.pubKey == "be" })
        // Nada além de guias entra no bloco: sem trechos, sem injeção.
        assertEquals("", questionPublicationBlock(emptyList()))
        assertEquals(
            "## SEÇÃO ATUAL\nTítulo: X",
            combineContextBlocks("## SEÇÃO ATUAL\nTítulo: X", emptyList(), emptyList()),
        )
    }

    @Test
    fun fluxoPerguntaSemRef_naoInjetaNada() {
        val refs = RefDetector.detect("faca um resumo do ponto 2")
            .filter { it.kind == RefDetector.Kind.BOOK && !isGuidePubRef(it) }

        assertTrue(refs.isEmpty())
        assertEquals("", questionPublicationBlock(emptyList()))
    }

    @Test
    fun fluxoGate_citacaoDoTrechoInjetado_ePreservada() {
        val texto = "O livro diz: “O perdão é uma das maiores provas de amor que podemos dar”."
        val semInjecao = GroundednessVerifier.verify(texto, listOf("fonte sem o trecho"))
        val comInjecao = GroundednessVerifier.verify(
            texto,
            listOf("O perdão é uma das maiores provas de amor que podemos dar"),
        )

        assertTrue("sem o trecho o gate remove a citação", semInjecao.hasRemovals)
        assertFalse("com o trecho injetado a citação é legítima", comInjecao.hasRemovals)
    }

    @Test
    fun fluxoPublicacaoSemUnidade_naoInventaTrecho() = runBlocking {
        // Ref válida mas sem unidade citada ("lff" puro): o resolvedor não
        // devolve texto — e publicationHitOf não fabrica hit.
        val resolved = resolver().resolvePublication(PublicationRef(symbol = "lff"))

        assertEquals(ReferenceStatus.UNRESOLVED, resolved.status)
        assertEquals(null, publicationHitOf(resolved, "lff"))
    }
}
