package com.bettertalker.app.data.s34

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.db.TransactionRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TRUST-2B3B-B1b / QA-FIX-43 P3 — cenários 17/18 com interleavings
 * determinísticos (barreiras `CompletableDeferred`, sem sleeps).
 *
 * Sequência REAL do adaptador (verifyLocked, dentro de `transactionRunner.run`):
 *   1) attachmentDao.get(attachmentId)
 *   2) passageDao.findByRefsIn(attachmentId, [canonicalRef])
 *   3) passageDao.findByTextInAttachment(attachmentId, text, 2)  // duplicatas
 *
 * Positivos: [RoomTransactionRunner] REAL (produção), com wrapper que registra
 * invocação — nunca um runner que "imita" transação.
 * Negativos: [PassThroughRunner] — sem isolamento — a mutação é aplicada entre
 * as leituras e o resultado DEVE divergir de RECORD_MATCH.
 */
@RunWith(AndroidJUnit4::class)
class NwtRoomIndexIdentityAdapterTransactionalInstrumentedTest {

    private lateinit var db: AppDatabase

    private val attachmentId = "att-b1b"
    private val ref = "Mt 24:14"
    private val text = "Texto sintético de Mateus 24:14."

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Timeout com diagnóstico da etapa responsável — F5. */
    private suspend fun <T> stage(name: String, signal: Deferred<T>): T = try {
        withTimeout(30_000) { signal.await() }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("Timeout (30s) na etapa '$name' — barreira/ordem de leituras não atingida", e)
    }

    private suspend fun joinStage(name: String, job: Job) = try {
        withTimeout(30_000) { job.join() }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("Timeout (30s) ao aguardar o job '$name'", e)
    }

    private fun tnmAttachment(
        id: String = attachmentId,
        indexed: Boolean = true,
        status: String = "ready",
        baseSlot: String? = "nwt",
        symbol: String? = "nwt",
        kind: String = "epub",
        sha256: String? = "a".repeat(64),
    ) = AttachmentEntity(
        id = id,
        noteId = "note-1",
        fileName = "nwt_T.epub",
        kind = kind,
        sizeBytes = 100,
        appPath = "/tmp/nwt.epub",
        indexed = indexed,
        addedAt = 1L,
        baseSlot = baseSlot,
        status = status,
        sha256 = sha256,
    )

    private fun passage(
        id: String = "p-1",
        attachmentId: String = this.attachmentId,
        ref: String = this.ref,
        text: String = this.text,
        section: String = "Mateus 24",
        ord: Int = 1,
    ) = PassageEntity(
        id = id,
        attachmentId = attachmentId,
        text = text,
        normalized = text.lowercase(),
        section = section,
        ref = ref,
        ord = ord,
    )

    private fun claim(
        passageId: String? = "p-1",
        canonicalRef: String = ref,
        ord: Int? = 1,
        section: String? = "Mateus 24",
        text: String = this.text,
        attachmentId: String = this.attachmentId,
    ) = NwtIndexedUnit(
        attachmentId = attachmentId,
        passageId = passageId,
        canonicalRef = canonicalRef,
        ord = ord,
        text = text,
        section = section,
    )

    // ---------- runner de PRODUÇÃO + prova de invocação (F5) ----------

    private class InstrumentedRoomTransactionRunner(
        db: AppDatabase,
        private val txStarted: CompletableDeferred<Unit>,
    ) : TransactionRunner {
        val invoked = AtomicBoolean(false)
        private val delegate = RoomTransactionRunner(db)
        override suspend fun <R> run(block: suspend () -> R): R {
            invoked.set(true)
            txStarted.complete(Unit)
            return delegate.run(block)
        }
    }

    private class PassThroughRunner : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }

    // ---------- DAOs instrumentados: barreiras determinísticas (F5) ----------

    /** Sinaliza após `get()` e PAUSA antes de devolver — controla a janela antes de findByRefsIn. */
    private class GatedAttachmentDao(
        private val delegate: AttachmentDao,
        private val afterGet: CompletableDeferred<Unit>,
        private val releaseGet: CompletableDeferred<Unit>,
    ) : AttachmentDao by delegate {
        override suspend fun get(id: String): AttachmentEntity? {
            val result = delegate.get(id)
            afterGet.complete(Unit)
            releaseGet.await()
            return result
        }
    }

    /** Sinaliza após `findByRefsIn` (sem pausa). */
    private class SignalingPassageDao(
        private val delegate: PassageDao,
        private val afterFindByRefsIn: CompletableDeferred<Unit>,
    ) : PassageDao by delegate {
        override suspend fun findByRefsIn(
            attachmentId: String,
            refs: List<String>,
        ): List<PassageEntity> {
            val result = delegate.findByRefsIn(attachmentId, refs)
            afterFindByRefsIn.complete(Unit)
            return result
        }
    }

    /** Sinaliza após `findByRefsIn` e PAUSA — barreira entre a 2.ª e a 3.ª leitura. */
    private class GatedSignalingPassageDao(
        private val delegate: PassageDao,
        private val afterFindByRefsIn: CompletableDeferred<Unit>,
        private val releaseAfterFindByRefsIn: CompletableDeferred<Unit>,
    ) : PassageDao by delegate {
        override suspend fun findByRefsIn(
            attachmentId: String,
            refs: List<String>,
        ): List<PassageEntity> {
            val result = delegate.findByRefsIn(attachmentId, refs)
            afterFindByRefsIn.complete(Unit)
            releaseAfterFindByRefsIn.await()
            return result
        }
    }

    /** Sinaliza o INÍCIO da exclusão — escrita concorrente. */
    private class DeletingAttachmentDao(
        private val delegate: AttachmentDao,
        private val deleteStarted: CompletableDeferred<Unit>,
    ) : AttachmentDao by delegate {
        override suspend fun delete(id: String) {
            deleteStarted.complete(Unit)
            delegate.delete(id)
        }
    }

    /** Sinaliza o INÍCIO da duplicação — escrita concorrente. */
    private class SignalingUpdateDao(
        private val delegate: PassageDao,
        private val updateStarted: CompletableDeferred<Unit>,
    ) : PassageDao by delegate {
        override suspend fun insertAll(items: List<PassageEntity>) {
            updateStarted.complete(Unit)
            delegate.insertAll(items)
        }
    }

    // ---------- 17 POSITIVO: exclusão concorrente (F2) ----------

    @Test
    fun cenario17_exclusaoConcorrenteForçadaNãoCertificaUnidadeInconsistente() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-17")))
        val txStarted = CompletableDeferred<Unit>()
        val attachmentRead = CompletableDeferred<Unit>()
        val releaseAttachmentRead = CompletableDeferred<Unit>()
        val refsRead = CompletableDeferred<Unit>()
        val deleteStarted = CompletableDeferred<Unit>()
        val runner = InstrumentedRoomTransactionRunner(db, txStarted)
        val adapterAttachmentDao = GatedAttachmentDao(db.attachmentDao(), attachmentRead, releaseAttachmentRead)
        val adapterPassageDao = SignalingPassageDao(db.passageDao(), refsRead)

        val resultDef = CompletableDeferred<NwtRoomIndexIdentityResult>()
        val verifyJob = launch {
            resultDef.complete(
                NwtRoomIndexIdentityAdapter.verify(
                    attachmentId = attachmentId,
                    canonicalRef = ref,
                    claim = claim(passageId = "p-17"),
                    attachmentDao = adapterAttachmentDao,
                    passageDao = adapterPassageDao,
                    transactionRunner = runner,
                )
            )
        }
        // F5: transação iniciada + 1.ª leitura concluída (barreira — tx aberta).
        stage("17/txStarted", txStarted)
        stage("17/attachmentRead", attachmentRead)

        // Exclusão iniciada DURANTE a transação.
        val deleteJob = launch {
            DeletingAttachmentDao(db.attachmentDao(), deleteStarted).delete(attachmentId)
            db.passageDao().deleteForAttachment(attachmentId)
        }
        stage("17/deleteStarted", deleteStarted)
        // F5: written started while the transaction is open; NÃO há join aqui.

        // Libera a verificação; a transação termina ANTES da exclusão ser aguardada.
        releaseAttachmentRead.complete(Unit)
        val result = stage("17/verifyResult", resultDef)
        joinStage("17/verifyJob", verifyJob)

        // Somente AGORA aguarda a exclusão (evita dependência circular).
        joinStage("17/deleteJob", deleteJob)

        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertNotNull(result.unit)
        assertEquals(attachmentId, result.unit!!.attachmentId)
        assertEquals("p-17", result.unit.passageId)
        assertEquals(text, result.unit.text)
        assertTrue(runner.invoked.get())
        stage("17/refsRead", refsRead)
        assertNull(db.attachmentDao().get(attachmentId))
    }

    // ---------- 17 NEGATIVO: PassThroughRunner (F3) ----------

    @Test
    fun cenario17negativo_runnerSemTransaçãoValidaEstadoInconsistente() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-17")))
        val attachmentRead = CompletableDeferred<Unit>()
        val releaseBeforeRefs = CompletableDeferred<Unit>()
        val adapterAttachmentDao = GatedAttachmentDao(db.attachmentDao(), attachmentRead, releaseBeforeRefs)

        val resultDef = CompletableDeferred<NwtRoomIndexIdentityResult>()
        launch {
            resultDef.complete(
                NwtRoomIndexIdentityAdapter.verify(
                    attachmentId = attachmentId,
                    canonicalRef = ref,
                    claim = claim(passageId = "p-17"),
                    attachmentDao = adapterAttachmentDao,
                    passageDao = db.passageDao(),
                    transactionRunner = PassThroughRunner(),
                )
            )
        }
        // get() concluído; adaptador pausado ANTES de findByRefsIn.
        stage("17neg/attachmentRead", attachmentRead)

        // Exclusão observada imediatamente (sem transação).
        val deleteJob = launch {
            db.attachmentDao().delete(attachmentId)
            db.passageDao().deleteForAttachment(attachmentId)
        }
        joinStage("17neg/deleteJob", deleteJob)

        releaseBeforeRefs.complete(Unit)
        val result = stage("17neg/result", resultDef)

        // Justificativa: findByRefsIn roda DEPOIS da exclusão → lista vazia →
        // RECORD_MISSING (adapter.verifyLocked, fluxo "records.isEmpty()").
        assertNotEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertEquals(NwtRoomIndexIdentityState.RECORD_MISSING, result.state)
    }

    // ---------- 18 POSITIVO: duplicação concorrente (F4) ----------

    @Test
    fun cenario18_alteracaoConcorrenteForçadaNãoCertificaUnidadeIncompativel() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-18", text = text)))
        val txStarted = CompletableDeferred<Unit>()
        val refsRead = CompletableDeferred<Unit>()
        val releaseAfterRefs = CompletableDeferred<Unit>()
        val updateStarted = CompletableDeferred<Unit>()
        val runner = InstrumentedRoomTransactionRunner(db, txStarted)
        val adapterPassageDao = GatedSignalingPassageDao(db.passageDao(), refsRead, releaseAfterRefs)

        val resultDef = CompletableDeferred<NwtRoomIndexIdentityResult>()
        val verifyJob = launch {
            resultDef.complete(
                NwtRoomIndexIdentityAdapter.verify(
                    attachmentId = attachmentId,
                    canonicalRef = ref,
                    claim = claim(passageId = "p-18"),
                    attachmentDao = db.attachmentDao(),
                    passageDao = adapterPassageDao,
                    transactionRunner = runner,
                )
            )
        }
        // F5: transação iniciada + 2.ª leitura concluída (barreira entre leituras).
        stage("18/txStarted", txStarted)
        stage("18/refsRead", refsRead)

        val updateJob = launch {
            SignalingUpdateDao(db.passageDao(), updateStarted).insertAll(
                listOf(passage(id = "p-18-dup", text = text)),
            )
        }
        stage("18/updateStarted", updateStarted)
        releaseAfterRefs.complete(Unit)
        val result = stage("18/verifyResult", resultDef)
        joinStage("18/verifyJob", verifyJob)

        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertNotNull(result.unit)
        assertEquals(text, result.unit!!.text)
        assertTrue(runner.invoked.get())
        // A duplicata é aplicada APÓS a transação; efeito final observável.
        joinStage("18/updateJob", updateJob)
        assertEquals(2, db.passageDao().findByTextInAttachment(attachmentId, text, 2).size)
    }

    // ---------- 18 NEGATIVO: PassThroughRunner (F4) ----------

    @Test
    fun cenario18negativo_runnerSemTransaçãoNãoCertificaUnitadeIncompativel() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-18", text = text)))
        val refsRead = CompletableDeferred<Unit>()
        val releaseAfterRefs = CompletableDeferred<Unit>()
        val adapterPassageDao = GatedSignalingPassageDao(db.passageDao(), refsRead, releaseAfterRefs)

        val resultDef = CompletableDeferred<NwtRoomIndexIdentityResult>()
        launch {
            resultDef.complete(
                NwtRoomIndexIdentityAdapter.verify(
                    attachmentId = attachmentId,
                    canonicalRef = ref,
                    claim = claim(passageId = "p-18"),
                    attachmentDao = db.attachmentDao(),
                    passageDao = adapterPassageDao,
                    transactionRunner = PassThroughRunner(),
                )
            )
        }
        stage("18neg/refsRead", refsRead)

        // Duplicação aplicada ENTRE findByRefsIn e findByTextInAttachment.
        val insertJob = launch {
            db.passageDao().insertAll(listOf(passage(id = "p-18-dup", text = text)))
        }
        joinStage("18neg/insertJob", insertJob)
        releaseAfterRefs.complete(Unit)
        val result = stage("18neg/result", resultDef)

        // A 3.ª leitura vê 2 registros → AMBIGUOUS_RECORD (≠ RECORD_MATCH).
        assertNotEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertEquals(NwtRoomIndexIdentityState.AMBIGUOUS_RECORD, result.state)
    }
}
