package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.buildDeepSeekPrompts
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.llm.GroundednessVerifier
import com.bettertalker.app.data.planning.DefaultDossierPromptBuilder
import com.bettertalker.app.data.repo.ScopedHit
import com.bettertalker.app.data.repo.canonicalBibleRefs
import com.bettertalker.app.data.repo.resolveBiblePassages
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.domain.planning.DefaultDossierBuilder
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.ReferenceResolver
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.planning.ResolvedReference
import com.bettertalker.app.domain.planning.SelectionContextContract
import com.bettertalker.app.domain.planning.SectionWithSubPoints
import com.bettertalker.app.domain.planning.retrieval.MethodIndex
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6 (acesso bíblico) — fluxo ponta a ponta travando T1–T5:
 * tópico → dossiê com refs dos sub-pontos → "## TEXTOS BÍBLICOS" no prompt;
 * pergunta → ref exata → versículo injetado → citável sem ser removido pelo
 * gate; Jó ≠ João; sem ref = sem injeção.
 */
class BibleAccessFlowTest {

    // ---------- fakes ----------

    private class FakeResolver(
        private val bible: Map<String, ResolvedReference> = emptyMap(),
    ) : ReferenceResolver {
        override suspend fun resolveBible(ref: String): ResolvedReference =
            bible[ref] ?: ResolvedReference(ref, ref, null, null, ReferenceStatus.UNRESOLVED)
        override suspend fun resolvePublication(ref: PublicationRef): ResolvedReference =
            ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.UNRESOLVED)
    }

    private class NoMethods : MethodIndex {
        override suspend fun findPrinciples(
            context: String,
            limit: Int,
            category: String?,
        ): List<String> = emptyList()
    }

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

    private fun passage(id: String, ref: String, text: String, normalized: String = text) =
        PassageEntity(id, "nwt", text, normalized, section = ref.substringBefore(":"), ref = ref)

    // ---------- T1: tópico (seção) resolve versículos dos sub-pontos ----------

    private fun section(id: String) = SpeechSection(
        id = id, noteId = "n1", order = 0, role = SectionRole.BODY, title = "FOMOS CRIADOS",
        minutes = 5, contentHtml = "", bibleRefs = emptyList(), publicationRefs = emptyList(),
        methodPrinciple = null, createdAt = 1L, updatedAt = 1L,
    )

    private fun subPoint(id: String, sectionId: String, refs: List<String>) = SubPoint(
        id = id, sectionId = sectionId, order = 0, outlineText = "ponto",
        bibleRefs = refs, publicationRefs = emptyList(), instruction = null,
        developedHtml = "", createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun fluxoTopico_versiculoDoSubPonto_entraNoMiniDiscurso() = runBlocking {
        val builder = DefaultDossierBuilder(
            FakeResolver(
                mapOf(
                    "Ec 3:11" to ResolvedReference(
                        "Ec 3:11", "Ec 3:11", "Ele fez tudo belo a seu tempo", "p1", ReferenceStatus.RESOLVED
                    ),
                )
            ),
            NoMethods(),
        )
        val doc = listOf(
            SectionWithSubPoints(section("s1"), listOf(subPoint("sp1", "s1", listOf("Ec 3:11")))),
        )
        val dossier = builder.build(
            SelectionContextContract("s1", null, "", ""),
            doc,
        )
        val prompt = DefaultDossierPromptBuilder().buildMiniSpeech(dossier)

        assertTrue(prompt.contains("## TEXTOS BÍBLICOS"))
        assertTrue(prompt.contains("Ele fez tudo belo a seu tempo"))
    }

    // ---------- T2+T3: pergunta resolve por ref e entra no prompt do chat ----------

    @Test
    fun fluxoPergunta_versiculoInjetadoNoPromptDoChat() = runBlocking {
        val dao = FakePassageDao(listOf(
            passage("p1", "Je 29:11", "“Pois eu sei muito bem o que tenho em mente para vocês”"),
        ))
        val verses = resolveBiblePassages(dao, "o que diz Jer. 29:11?", 4)
            .map { ScopedHit(it, "Tradução do Novo Mundo da Bíblia Sagrada") }
        val combined = combineContextBlocks("## SEÇÃO ATUAL\nTítulo: X", verses)

        // O prompt REAL do DeepSeek carrega o bloco (system).
        val prompts = buildDeepSeekPrompts(
            message = "o que diz Jer. 29:11?",
            history = emptyList(),
            isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null,
            blockMinutes = null,
            blockText = "",
            contextBlock = combined,
        )
        assertTrue(prompts.system.contains("## TEXTOS BÍBLICOS (referências da pergunta)"))
        assertTrue(prompts.system.contains("- Je 29:11:"))
        assertTrue(prompts.system.contains("Pois eu sei muito bem o que tenho em mente para vocês"))
    }

    @Test
    fun fluxoPergunta_biblePassagesRetornaTextoLiteral() = runBlocking {
        val dao = FakePassageDao(listOf(
            passage("p1", "Je 29:11", "texto literal 29:11"),
            passage("p2", "Je 29:12", "texto literal 29:12"),
        ))
        val out = resolveBiblePassages(dao, "Je 29:11-12", 4)

        assertEquals(listOf("Je 29:11", "Je 29:12"), out.map { it.ref })
        assertEquals("texto literal 29:11", out[0].text)
        assertEquals("texto literal 29:12", out[1].text)
    }

    // ---------- T5: Jó ≠ João ----------

    @Test
    fun fluxoJob_acentoViraJoENaoJoao() {
        assertEquals("Jó", RefDetector.detectBible("Jó 33:24").single().label)
        assertEquals(listOf("Jó 33:24"), canonicalBibleRefs("Jó 33:24"))
        assertFalse(canonicalBibleRefs("Jó 33:24").contains("Jo 33:24"))
    }

    // ---------- P4: sem ref na pergunta, sem injeção ----------

    @Test
    fun fluxoPerguntaSemRef_naoInjetaNada() {
        assertTrue(canonicalBibleRefs("faça um resumo do ponto 2").isEmpty())
        assertEquals("", questionBibleBlock(emptyList()))
        // O dossiê passa intacto (nenhum bloco extra).
        assertEquals("## SEÇÃO ATUAL\nTítulo: X", combineContextBlocks("## SEÇÃO ATUAL\nTítulo: X", emptyList()))
    }

    // ---------- gate: citação do versículo injetado não é removida ----------

    @Test
    fun fluxoGate_citacaoDoVersiculoInjetado_ePreservada() {
        val texto = "A resposta é: “Pois eu sei muito bem o que tenho em mente para vocês”."
        val semInjecao = GroundednessVerifier.verify(texto, listOf("fonte sem o versículo"))
        val comInjecao = GroundednessVerifier.verify(
            texto,
            listOf("“Pois eu sei muito bem o que tenho em mente para vocês”"),
        )

        assertTrue("sem o versículo o gate remove a citação", semInjecao.hasRemovals)
        assertFalse("com o versículo injetado a citação é legítima", comInjecao.hasRemovals)
    }
}
