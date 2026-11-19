package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.ConfirmedApproachWrite
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.copilot.hasCurrentApplication
import com.bettertalker.app.data.db.DecisionDao
import com.bettertalker.app.data.db.DecisionEntity
import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.db.toEntity
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.data.repo.DecisionMemoryRepository
import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.OutlineAngle
import com.bettertalker.app.domain.planning.OutlineProposal
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.SectionPlan
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class CaptureFakeSectionDao(
    var rows: List<SpeechSectionEntity> = emptyList(),
) : SpeechSectionDao {
    var failOnUpsert = false
    var upsertCalls = 0

    override suspend fun forNote(noteId: String): List<SpeechSectionEntity> =
        rows.filter { it.noteId == noteId }.sortedBy { it.order }

    override fun observeForNote(noteId: String): Flow<List<SpeechSectionEntity>> =
        flowOf(rows.filter { it.noteId == noteId }.sortedBy { it.order })

    override suspend fun get(id: String): SpeechSectionEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun upsert(section: SpeechSectionEntity) {
        if (failOnUpsert) error("falha simulada na seção")
        upsertCalls++
        rows = rows.filter { it.id != section.id } + section
    }

    override suspend fun upsertAll(sections: List<SpeechSectionEntity>) {
        sections.forEach { upsert(it) }
    }

    override suspend fun updateObjective(id: String, objective: String?, updatedAt: Long) {
        rows = rows.map {
            if (it.id == id) it.copy(objective = objective, updatedAt = updatedAt) else it
        }
    }

    override suspend fun updateAgreedApproach(
        id: String,
        agreedApproach: String?,
        updatedAt: Long,
    ) {
        rows = rows.map {
            if (it.id == id) {
                it.copy(agreedApproach = agreedApproach, updatedAt = updatedAt)
            } else {
                it
            }
        }
    }

    override suspend fun delete(section: SpeechSectionEntity) {
        rows = rows.filter { it.id != section.id }
    }

    override suspend fun deleteForNote(noteId: String) {
        rows = rows.filter { it.noteId != noteId }
    }
}

private class CaptureFakeDecisionDao : DecisionDao {
    var rows: List<DecisionEntity> = emptyList()
    var failOnUpsert = false
    var upsertCalls = 0

    override suspend fun forNote(noteId: String): List<DecisionEntity> =
        rows.filter { it.noteId == noteId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun get(id: String): DecisionEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun upsert(record: DecisionEntity) {
        if (failOnUpsert) error("falha simulada na decisão")
        upsertCalls++
        rows = rows.filter { it.id != record.id } + record
    }

    override suspend fun upsertAll(records: List<DecisionEntity>) {
        records.forEach { upsert(it) }
    }

    override suspend fun insertIfAbsent(record: DecisionEntity): Long {
        if (rows.any { it.id == record.id }) return -1L
        rows = rows + record
        return rows.size.toLong()
    }

    override suspend fun markSupersededIfActive(id: String, updatedAt: Long): Int {
        val existing = rows.firstOrNull { it.id == id } ?: return 0
        if (existing.decisionState != "ACTIVE") return 0
        rows = rows.map {
            if (it.id == id) {
                it.copy(decisionState = "SUPERSEDED", updatedAt = updatedAt)
            } else {
                it
            }
        }
        return 1
    }

    override suspend fun linkReplacementIfUnlinked(
        replacementId: String,
        previousId: String,
        expectedUpdatedAt: Long,
        updatedAt: Long,
    ): Int {
        val existing = rows.firstOrNull { it.id == replacementId } ?: return 0
        if (
            existing.supersedesId != null ||
            existing.decisionState != "ACTIVE" ||
            existing.updatedAt != expectedUpdatedAt
        ) {
            return 0
        }
        rows = rows.map {
            if (it.id == replacementId) {
                it.copy(supersedesId = previousId, updatedAt = updatedAt)
            } else {
                it
            }
        }
        return 1
    }

    override suspend fun deleteById(id: String) {
        rows = rows.filter { it.id != id }
    }

    override suspend fun deleteForNote(noteId: String) {
        rows = rows.filter { it.noteId != noteId }
    }

    override suspend fun countForNote(noteId: String): Int =
        rows.count { it.noteId == noteId }
}

private class CaptureRollbackRunner(
    private val sectionDao: CaptureFakeSectionDao,
    private val decisionDao: CaptureFakeDecisionDao,
) : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R {
        val sections = sectionDao.rows
        val decisions = decisionDao.rows
        return try {
            block()
        } catch (failure: Throwable) {
            sectionDao.rows = sections
            decisionDao.rows = decisions
            throw failure
        }
    }
}

/** 1C.2.1B1 — captura explícita do aceite estrutural, sem prompt. */
class StructureAcceptMemoryTest {

    private fun section(
        id: String = "s1",
        noteId: String = "n1",
        title: String = "Fé que age",
        minutes: Int = 5,
        contentHtml: String = "<p>texto atual da seção</p>",
        order: Int = 0,
        bibleRefs: List<String> = emptyList(),
        pubRefs: List<PublicationRef> = emptyList(),
        approach: String? = null,
        createdAt: Long = 1L,
        updatedAt: Long = 1L,
    ) = SpeechSection(
        id = id,
        noteId = noteId,
        order = order,
        role = SectionRole.BODY,
        title = title,
        minutes = minutes,
        contentHtml = contentHtml,
        bibleRefs = bibleRefs,
        publicationRefs = pubRefs,
        methodPrinciple = null,
        approvedAt = null,
        createdAt = createdAt,
        updatedAt = updatedAt,
    ).copy(agreedApproach = approach)

    private fun plan(
        title: String = "Fé que age",
        minutes: Int = 7,
        mainIdea: String = "Mostrar que o desejo de viver é legítimo.",
        bibleRefs: List<String> = listOf("Ec 3:11"),
        pubRefs: List<PublicationRef> = emptyList(),
    ) = SectionPlan(
        title = title,
        minutes = minutes,
        mainIdea = mainIdea,
        bibleRefs = bibleRefs,
        publicationRefs = pubRefs,
        methodPrinciple = null,
    )

    private fun proposal(
        summary: String = "Resumo da proposta.",
        plans: List<SectionPlan> = listOf(plan()),
        totalMinutes: Int = 10,
    ) = OutlineProposal(
        id = "outline-1",
        angle = OutlineAngle.PRACTICAL,
        audience = Audience.GENERAL,
        title = "Discurso",
        summary = summary,
        totalMinutes = totalMinutes,
        sections = plans,
    )

    private fun baseHash(
        title: String = "Fé que age",
        contentHtml: String = "<p>texto atual da seção</p>",
    ) = hashText(sectionProposalBlock(title, contentHtml))

    private fun request(
        proposalId: String = "sprop-1",
        noteId: String = "n1",
        sectionId: String = "s1",
        proposal: OutlineProposal = proposal(),
        plan: SectionPlan = plan(),
        summary: String = "Resumo da proposta.",
        sectionBaseHash: String = baseHash(),
        allowEmptyRefs: Boolean = false,
    ) = StructureAcceptRequest(
        proposalId = proposalId,
        noteId = noteId,
        sectionId = sectionId,
        proposal = proposal,
        plan = plan,
        summary = summary,
        sectionBaseHash = sectionBaseHash,
        allowEmptyRefs = allowEmptyRefs,
    )

    private fun ui(
        id: String = "sprop-1",
        noteId: String = "n1",
        proposal: OutlineProposal = proposal(),
        plan: SectionPlan = plan(),
        summary: String = "Resumo da proposta.",
        sectionBaseHash: String = baseHash(),
    ) = CopilotViewModel.StructureProposalUi(
        id = id,
        noteId = noteId,
        sectionId = "s1",
        proposal = proposal,
        plan = plan,
        summary = summary,
        sectionBaseHash = sectionBaseHash,
    )

    private fun service(
        sectionDao: CaptureFakeSectionDao,
        decisionDao: CaptureFakeDecisionDao,
    ): Pair<StructureAcceptService, CaptureRollbackRunner> {
        val runner = CaptureRollbackRunner(sectionDao, decisionDao)
        return StructureAcceptService(
            transactionRunner = runner,
            sectionDao = sectionDao,
            decisionMemory = DecisionMemoryRepository(decisionDao, runner),
        ) to runner
    }

    @Test
    fun aceiteValidoCriaExatamenteUmaDecisao() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        val result = accept.accept(request(), now = 20L)

        assertTrue(result is StructureAcceptResult.Applied)
        assertEquals(1, decisionDao.rows.size)
        val decision = (result as StructureAcceptResult.Applied).decision
        assertEquals(decision, decisionDao.rows.single().toDomain())
        assertEquals("dec-structure-sprop-1", decision.id)
        assertEquals("n1", decision.noteId)
        assertEquals("s1", decision.sectionId)
        assertEquals("s1", decision.originalSectionId)
        assertEquals(DecisionScope.SECTION, decision.scope)
        assertEquals(DecisionKind.CONFIRMED, decision.kind)
        assertEquals(DecisionState.ACTIVE, decision.decisionState)
        assertEquals(SectionLink.VALID, decision.sectionLink)
        assertEquals(DecisionSource.STRUCTURE_ACCEPT, decision.source)
        assertEquals("sprop-1", decision.sourceProposalId)
        assertEquals("Resumo da proposta.", decision.text)
        assertEquals(20L, decision.createdAt)
        assertEquals(20L, decision.updatedAt)
        assertNull(decision.supersedesId)

        val storedSection = sectionDao.rows.single()
        assertEquals(7, storedSection.minutes)
        assertEquals(20L, storedSection.updatedAt)
        assertTrue(storedSection.agreedApproach!!.contains("Resumo da proposta."))
    }

    @Test
    fun mesmoAceiteRepetidoNaoCriaDuplicata() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        val first = accept.accept(request(), now = 20L)
        val second = accept.accept(request(), now = 30L)

        assertTrue(first is StructureAcceptResult.Applied)
        assertTrue(second is StructureAcceptResult.AlreadyApplied)
        assertEquals(
            (first as StructureAcceptResult.Applied).decision,
            (second as StructureAcceptResult.AlreadyApplied).decision,
        )
        assertEquals(1, decisionDao.rows.size)
        assertEquals(20L, decisionDao.rows.single().createdAt)
        assertEquals(20L, decisionDao.rows.single().updatedAt)
        // O segundo aceite não reaplica a seção.
        assertEquals(1, sectionDao.upsertCalls)
    }

    @Test
    fun repeticaoAposEdicaoPosteriorNaoAvaliaStale() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        val first = accept.accept(request(), now = 20L) as StructureAcceptResult.Applied
        // O usuário edita a seção depois do aceite.
        sectionDao.rows = listOf(
            section(contentHtml = "<p>editado depois</p>", updatedAt = 25L).toEntity(),
        )

        val repeated = accept.accept(
            request(sectionBaseHash = baseHash()),
            now = 30L,
        )

        // O evento já persistido vence o gate de stale e não mexe em nada.
        assertTrue(repeated is StructureAcceptResult.AlreadyApplied)
        assertEquals(first.decision, (repeated as StructureAcceptResult.AlreadyApplied).decision)
        assertEquals(1, decisionDao.rows.size)
        assertEquals(20L, decisionDao.rows.single().updatedAt)
        assertEquals("<p>editado depois</p>", sectionDao.rows.single().contentHtml)
        assertEquals(25L, sectionDao.rows.single().updatedAt)
        assertEquals(1, sectionDao.upsertCalls)
    }

    @Test
    fun repeticaoComPropostaInvalidaPosteriorAindaEhIdempotente() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        accept.accept(request(), now = 20L)
        val invalid = proposal(summary = "")
        val repeated = accept.accept(
            request(proposal = invalid, summary = ""),
            now = 30L,
        )

        assertTrue(repeated is StructureAcceptResult.AlreadyApplied)
        assertEquals(1, decisionDao.rows.size)
        assertEquals(20L, decisionDao.rows.single().updatedAt)
        assertEquals(1, sectionDao.upsertCalls)
    }

    @Test
    fun colisaoDeIdComOutraSecaoFalhaSemAlterarNada() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)
        val otherSection = section(id = "s9", noteId = "n1")
        val colliding = buildStructureAcceptDecision(
            proposalId = "sprop-1",
            current = otherSection,
            updated = otherSection,
            summary = "Outra proposta",
            plan = plan(),
            now = 10L,
        )
        decisionDao.upsert(colliding.toEntity())

        val error = runCatching { accept.accept(request(), now = 20L) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(colliding, decisionDao.rows.single().toDomain())
        assertEquals(5, sectionDao.rows.single().minutes)
        assertEquals(1L, sectionDao.rows.single().updatedAt)
        assertEquals(0, sectionDao.upsertCalls)
    }

    @Test
    fun colisaoDeIdComOutraNotaFalhaSemAlterarNada() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)
        val otherNoteSection = section(id = "s1", noteId = "n2")
        val colliding = buildStructureAcceptDecision(
            proposalId = "sprop-1",
            current = otherNoteSection,
            updated = otherNoteSection,
            summary = "Outra proposta",
            plan = plan(),
            now = 10L,
        )
        decisionDao.upsert(colliding.toEntity())

        val error = runCatching { accept.accept(request(), now = 20L) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(colliding, decisionDao.rows.single().toDomain())
        assertEquals(1L, sectionDao.rows.single().updatedAt)
        assertEquals(0, sectionDao.upsertCalls)
    }

    @Test
    fun colisaoDeOrigemFalhaSemAlterarNada() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)
        val current = section()
        val colliding = buildStructureAcceptDecision(
            proposalId = "sprop-1",
            current = current,
            updated = current,
            summary = "Outra proposta",
            plan = plan(),
            now = 10L,
        ).copy(source = DecisionSource.EXPLICIT_USER)
        decisionDao.upsert(colliding.toEntity())

        val error = runCatching { accept.accept(request(), now = 20L) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(colliding, decisionDao.rows.single().toDomain())
        assertEquals(0, sectionDao.upsertCalls)
    }

    @Test
    fun avisoDeApproachCheioDistingueDecisaoDoCampoLegado() {
        val notice = structureAppliedNotice(ConfirmedApproachWrite.NoSpace)

        assertEquals(STRUCTURE_APPROACH_NOT_STORED_NOTICE, notice)
        assertTrue(notice.contains("decisão registrada"))
        assertTrue(notice.contains("campo legado"))
        assertTrue(notice.contains("preservado"))
        assertFalse(notice.contains("não foi memorizada"))
    }

    @Test
    fun propostaRejeitadaNaoCriaDecisao() {
        val decisionDao = CaptureFakeDecisionDao()
        val rejected = rejectedStructureProposal(ui())

        assertEquals(STRUCTURE_REJECTED_NOTICE, rejected.notice)
        assertFalse(rejected.applied)
        assertTrue(decisionDao.rows.isEmpty())
    }

    @Test
    fun propostaObsoletaNaoCriaDecisao() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        val result = accept.accept(
            request(sectionBaseHash = "hash-antigo"),
            now = 20L,
        )

        assertEquals(StructureAcceptResult.Stale, result)
        assertTrue(decisionDao.rows.isEmpty())
        assertEquals(5, sectionDao.rows.single().minutes)
        assertEquals(1L, sectionDao.rows.single().updatedAt)
    }

    @Test
    fun propostaInvalidaNaoCriaDecisao() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        val invalid = proposal(summary = "")
        val result = accept.accept(
            request(proposal = invalid, summary = ""),
            now = 20L,
        )

        assertEquals(StructureAcceptResult.Invalid, result)
        assertTrue(decisionDao.rows.isEmpty())
        assertEquals(5, sectionDao.rows.single().minutes)
    }

    @Test
    fun falhaNaPersistenciaProvocaRollback() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao().apply { failOnUpsert = true }
        val (accept, _) = service(sectionDao, decisionDao)

        val error = runCatching { accept.accept(request(), now = 20L) }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(decisionDao.rows.isEmpty())
        // A seção aplicada foi desfeita junto com a decisão.
        assertEquals(5, sectionDao.rows.single().minutes)
        assertEquals(1L, sectionDao.rows.single().updatedAt)
    }

    @Test
    fun falhaAoAplicarSecaoNaoCriaDecisao() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
            .apply { failOnUpsert = true }
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        val error = runCatching { accept.accept(request(), now = 20L) }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(decisionDao.rows.isEmpty())
        assertEquals(5, sectionDao.rows.single().minutes)
    }

    @Test
    fun decisaoPertenceANotaESecaoCorretas() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(
            listOf(section(id = "s9", noteId = "n7", title = "Outra seção").toEntity()),
        )
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        val result = accept.accept(
            request(
                noteId = "n7",
                sectionId = "s9",
                sectionBaseHash = baseHash(title = "Outra seção"),
            ),
            now = 20L,
        )

        val decision = (result as StructureAcceptResult.Applied).decision
        assertEquals("n7", decision.noteId)
        assertEquals("s9", decision.sectionId)
        assertEquals("s9", decision.originalSectionId)
    }

    @Test
    fun aceiteDeUmaNotaNaoModificaDecisoesDeOutraNota() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(
            listOf(
                section(id = "s1", noteId = "n1").toEntity(),
                section(id = "s2", noteId = "n2").toEntity(),
            ),
        )
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        val fromN2 = accept.accept(
            request(proposalId = "sprop-2", noteId = "n2", sectionId = "s2"),
            now = 20L,
        ) as StructureAcceptResult.Applied
        val fromN1 = accept.accept(
            request(proposalId = "sprop-1", sectionId = "s1"),
            now = 30L,
        ) as StructureAcceptResult.Applied

        assertEquals(2, decisionDao.rows.size)
        assertEquals("n1", fromN1.decision.noteId)
        assertEquals("n2", fromN2.decision.noteId)
        assertEquals(
            fromN2.decision,
            decisionDao.rows.first {
                it.id == structureAcceptDecisionId("sprop-2")
            }.toDomain(),
        )
        assertEquals(20L, fromN2.decision.updatedAt)
    }

    @Test
    fun snapshotsEProvenienciaSaoPreservados() {
        val current = section(title = "Ponto A", order = 0, contentHtml = "<p>A</p>")
        val updated = current.copy(
            title = "Ponto B",
            order = 2,
            contentHtml = "<p>B</p>",
            updatedAt = 20L,
        )

        val decision = buildStructureAcceptDecision(
            proposalId = "sprop-9",
            current = current,
            updated = updated,
            summary = "Resumo exato",
            plan = plan(),
            now = 20L,
        )

        assertEquals("Ponto B", decision.sectionTitleSnapshot)
        assertEquals(2, decision.sectionOrderSnapshot)
        assertEquals(
            hashText(sectionProposalBlock("Ponto B", "<p>B</p>")),
            decision.sectionContentHashSnapshot,
        )
        assertEquals("Ponto A", decision.originalSectionTitleSnapshot)
        assertEquals(0, decision.originalSectionOrderSnapshot)
        assertEquals(
            hashText(sectionProposalBlock("Ponto A", "<p>A</p>")),
            decision.originalSectionContentHashSnapshot,
        )
        assertEquals("sprop-9", decision.sourceProposalId)
        assertEquals(DecisionSource.STRUCTURE_ACCEPT, decision.source)
    }

    @Test
    fun aceiteEstruturalNaoMarcaAplicacaoDeTexto() = runBlocking {
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        val decision = (accept.accept(request(), now = 20L) as StructureAcceptResult.Applied)
            .decision

        assertEquals(ApplicationState.NOT_APPLIED, decision.applicationState)
        assertNull(decision.appliedAt)
        assertNull(decision.appliedHash)
        assertFalse(decision.hasCurrentApplication())
    }

    @Test
    fun idDeterministicoETextoExato() {
        assertEquals(
            structureAcceptDecisionId("sprop-1"),
            structureAcceptDecisionId("sprop-1"),
        )
        assertFalse(
            structureAcceptDecisionId("sprop-1") ==
                structureAcceptDecisionId("sprop-2"),
        )
        assertEquals(
            "Resumo exato",
            structureAcceptDecisionText("  Resumo exato  ", plan()),
        )
        assertEquals(
            "Ideia central",
            structureAcceptDecisionText("   ", plan(mainIdea = "Ideia central")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            structureAcceptDecisionText(" ", plan(mainIdea = " "))
        }
    }

    @Test
    fun uiMarcaSucessoSoParaResultadoPersistido() {
        val base = ui()
        val decision = buildStructureAcceptDecision(
            proposalId = "sprop-1",
            current = section(),
            updated = section(updatedAt = 20L),
            summary = "Resumo",
            plan = plan(),
            now = 20L,
        )

        val applied = StructureAcceptResult.Applied(
            ConfirmedApproachWrite.Stored("x"),
            decision,
        ).toStructureProposalUi(base)
        assertTrue(applied.applied)
        assertEquals(STRUCTURE_APPLIED_NOTICE, applied.notice)

        val already = StructureAcceptResult.AlreadyApplied(decision)
            .toStructureProposalUi(base)
        assertTrue(already.applied)
        assertEquals(STRUCTURE_APPLIED_NOTICE, already.notice)

        val noSpace = StructureAcceptResult.Applied(
            ConfirmedApproachWrite.NoSpace,
            decision,
        ).toStructureProposalUi(base)
        assertTrue(noSpace.applied)
        assertEquals(STRUCTURE_APPROACH_NOT_STORED_NOTICE, noSpace.notice)

        val stale = StructureAcceptResult.Stale.toStructureProposalUi(base)
        assertFalse(stale.applied)
        assertEquals(STRUCTURE_STALE_NOTICE, stale.notice)

        val missing = StructureAcceptResult.MissingSection.toStructureProposalUi(base)
        assertFalse(missing.applied)
        assertEquals(STRUCTURE_STALE_NOTICE, missing.notice)

        val invalid = StructureAcceptResult.Invalid.toStructureProposalUi(base)
        assertFalse(invalid.applied)
        assertEquals(STRUCTURE_INVALID_NOTICE, invalid.notice)

        val failed = structureAcceptFailure(base)
        assertFalse(failed.applied)
        assertEquals(STRUCTURE_APPLY_FAILED_NOTICE, failed.notice)
    }

    @Test
    fun decisaoNaoEntraNoPromptDoChatNestaEtapa() = runBlocking {
        val unique = "DECISÃO-B1-NÃO-PROMPT"
        val sectionDao = CaptureFakeSectionDao(listOf(section().toEntity()))
        val decisionDao = CaptureFakeDecisionDao()
        val (accept, _) = service(sectionDao, decisionDao)

        val result = accept.accept(
            request(
                proposal = proposal(summary = unique),
                summary = unique,
            ),
            now = 20L,
        ) as StructureAcceptResult.Applied
        val decision = result.decision

        val prompt = buildChatPrompt(
            message = "oi",
            history = emptyList(),
            isFirstMessage = true,
            pack = ContextPack(
                contentSources = emptyList(),
                trainingSources = emptyList(),
            ),
            blockTitle = null,
            blockMinutes = null,
            blockText = "",
        )

        // A B1 é write-only: nenhum prompt lê o ledger nesta etapa.
        assertFalse(prompt.contains(decision.text))
        assertFalse(prompt.contains(unique))
    }
}
