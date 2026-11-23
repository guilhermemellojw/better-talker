package com.bettertalker.app.data.s34

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.db.TransactionRunner
import kotlinx.coroutines.CompletableDeferred
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
 * TRUST-2B3B-B1b — cenários 17/18 do contrato transacional do adaptador,
 * redevensados pelo QA-FIX-43 (INT-P4) para forçar **interleavings** entre
 * leituras sucessivas do adaptador.
 *
 * Cenários POSITIVOS: [GatedTransactionRunner] (real — Room `withTransaction`),
 * mutação concorrente iniciada quando o read acabou e pausada na barreira —
 * verificação continua sob a ISO pela transação-tempo → `RECORD_MATCH`.
 *
 * Controles NEGATIVOS: [PassThroughRunner] (sem transação), a mesma mutação
 * se aplica IMEDIATAMENTE entre reads — os testes ASSERTAM o estado EXPOSTO
 * inconsistente — nunca `RECORD_MATCH` a partir de estados mistos.
 *
 * `gatedRunner.invocations` prova invocação efetiva do `TransactionRunner`.
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

    // ---------- runners & DAO-signals (barreiras determinísticas) ----------

    private class GatedTransactionRunner(
        private val db: AppDatabase,
        private val releaseTx: CompletableDeferred<Unit>,
    ) : TransactionRunner {
        val invocations = AtomicBoolean(false)
        override suspend fun <R> run(block: suspend () -> R): R =
            db.withTransaction {
                invocations.set(true)
                val result = block()
                releaseTx.await()
                result
            }
    }

    private class PassThroughRunner : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }

    private class SignalingAttachmentDao(
        private val delegate: AttachmentDao,
        private val readOne: CompletableDeferred<Unit>,
    ) : AttachmentDao by delegate {
        override suspend fun get(id: String): AttachmentEntity? {
            val result = delegate.get(id)
            readOne.complete(Unit)
            return result
        }
    }

    /** DAO que sinaliza após `findByRefsIn` retornar (em ambos os runners). */
    private class SignalingPassageDao(
        private val delegate: PassageDao,
        private val readTwo: CompletableDeferred<Unit>,
    ) : PassageDao by delegate {
        override suspend fun findByRefsIn(
            attachmentId: String,
            refs: List<String>,
        ): List<PassageEntity> {
            val result = delegate.findByRefsIn(attachmentId, refs)
            readTwo.complete(Unit)
            return result
        }
    }

    /** DAO que sinaliza após `findByRefsIn` — e desbloqueia a verificação. */
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

    private class DeletingAttachmentDao(
        private val delegate: AttachmentDao,
        private val deleteStarted: CompletableDeferred<Unit>,
    ) : AttachmentDao by delegate {
        override suspend fun delete(id: String) {
            deleteStarted.complete(Unit)
            delegate.delete(id)
        }
    }

    private class SignalingUpdateDao(
        private val delegate: PassageDao,
        private val updateStarted: CompletableDeferred<Unit>,
    ) : PassageDao by delegate {
        override suspend fun insertAll(items: List<PassageEntity>) {
            updateStarted.complete(Unit)
            delegate.insertAll(items)
        }
    }

    // ---------- 17 (positivo): exclusão concorrente, interleavar forçado ----------

    @Test
    fun cenario17_exclusaoConcorrenteForçadaNãoCertificaUnidadeInconsistente() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-17")))
        val readOneDone = CompletableDeferred<Unit>()
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseTx = CompletableDeferred<Unit>()
        val adapterDao = SignalingAttachmentDao(db.attachmentDao(), readOneDone)
        val gatedRunner = GatedTransactionRunner(db, releaseTx)

        val resultDef = CompletableDeferred<NwtRoomIndexIdentityResult>()
        val verifyJob = launch {
            resultDef.complete(
                NwtRoomIndexIdentityAdapter.verify(
                    attachmentId = attachmentId,
                    canonicalRef = ref,
                    claim = claim(passageId = "p-17"),
                    attachmentDao = adapterDao,
                    passageDao = db.passageDao(),
                    transactionRunner = gatedRunner,
                )
            )
        }
        // Read 1 concluída — transação aberta.
        withTimeout(30_000) { readOneDone.await() }

        // Escrita concorrente iniciada durante a transação.
        val deleteJob = launch {
            DeletingAttachmentDao(db.attachmentDao(), deleteStarted).delete(attachmentId)
            db.passageDao().deleteForAttachment(attachmentId)
        }
        withTimeout(30_000) { deleteStarted.await() }
        withTimeout(30_000) { deleteJob.join() }

        // Libera verificação; transação commita.
        releaseTx.complete(Unit)
        val result = withTimeout(30_000) { resultDef.await() }
        withTimeout(30_000) { verifyJob.join() }

        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertNotNull(result.unit)
        assertEquals(attachmentId, result.unit!!.attachmentId)
        assertEquals("p-17", result.unit.passageId)
        assertEquals(text, result.unit.text)
        assertTrue(gatedRunner.invocations.get())
        assertNull(db.attachmentDao().get(attachmentId))
    }

    // ---------- 17 (negativo): sem transação, o estado inconsistente é EXPOSTO ----------

    @Test
    fun cenario17negativo_runnerSemTransaçãoValidaEstadoInconsistente() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-17")))
        val readTwo = CompletableDeferred<Unit>()
        val adapterDao = SignalingPassageDao(db.passageDao(), readTwo)
        val runner = PassThroughRunner()

        val resultDef = CompletableDeferred<NwtRoomIndexIdentityResult>()
        launch {
            resultDef.complete(
                NwtRoomIndexIdentityAdapter.verify(
                    attachmentId = attachmentId,
                    canonicalRef = ref,
                    claim = claim(passageId = "p-17"),
                    attachmentDao = db.attachmentDao(),
                    passageDao = adapterDao,
                    transactionRunner = runner,
                )
            )
        }
        // Read 1 (findByRefsIn) concluída; verificação continua.
        withTimeout(30_000) { readTwo.await() }

        // Escrita concorrente — observável IMEDIATAMENTE (sem transação).
        val deleteJob = launch {
            db.attachmentDao().delete(attachmentId)
            db.passageDao().deleteForAttachment(attachmentId)
        }
        withTimeout(30_000) { deleteJob.join() }

        val result = withTimeout(30_000) { resultDef.await() }

        // Controle negativo: EXPÕE a inconsistência.
        assertNotEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertEquals(NwtRoomIndexIdentityState.RECORD_MISSING, result.state)
    }

    // ---------- 18 (positivo): alteração concorrente da passagem, interleavar forçado ----------

    @Test
    fun cenario18_alteracaoConcorrenteForçadaNãoCertificaUnidadeIncompativel() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-18", text = text)))
        val afterFindRefsIn = CompletableDeferred<Unit>()
        val releaseAfterFindRefsIn = CompletableDeferred<Unit>()
        val updateStarted = CompletableDeferred<Unit>()
        val releaseTx = CompletableDeferred<Unit>()
        val adapterDao = GatedSignalingPassageDao(
            db.passageDao(), afterFindRefsIn, releaseAfterFindRefsIn,
        )
        val gatedRunner = GatedTransactionRunner(db, releaseTx)

        val resultDef = CompletableDeferred<NwtRoomIndexIdentityResult>()
        val verifyJob = launch {
            resultDef.complete(
                NwtRoomIndexIdentityAdapter.verify(
                    attachmentId = attachmentId,
                    canonicalRef = ref,
                    claim = claim(passageId = "p-18"),
                    attachmentDao = db.attachmentDao(),
                    passageDao = adapterDao,
                    transactionRunner = gatedRunner,
                )
            )
        }
        // Verificação pausada imediatamente após findByRefsIn (dentro da tx aberta).
        withTimeout(30_000) { afterFindRefsIn.await() }

        // Escrita concorrente iniciada durante a transação.
        val updateJob = launch {
            SignalingUpdateDao(db.passageDao(), updateStarted).insertAll(
                listOf(passage(id = "p-18-dup", text = text)),
            )
        }
        withTimeout(30_000) { updateStarted.await() }

        // Libera verificação, transaction commita.
        releaseAfterFindRefsIn.complete(Unit)
        releaseTx.complete(Unit)
        val result = withTimeout(30_000) { resultDef.await() }
        withTimeout(30_000) { verifyJob.join() }

        // Visão consistente: RECORD_MATCH, texto original.
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertNotNull(result.unit)
        assertEquals(text, result.unit!!.text)
        assertTrue(gatedRunner.invocations.get())
        // Efeito final da escrita concorrente aplicada (aula final).
        withTimeout(30_000) { updateJob.join() }
        assertEquals(2, db.passageDao().findByTextInAttachment(attachmentId, text, 2).size)
    }

    // ---------- 18 (negativo): sem transação, unidade expande estados mistos ----------

    @Test
    fun cenario18negativo_runnerSemTransaçãoNãoCertificaUnitadeIncompativel() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-18", text = text)))
        val afterFindRefsIn = CompletableDeferred<Unit>()
        val releaseAfterFindRefsIn = CompletableDeferred<Unit>()
        val adapterDao = GatedSignalingPassageDao(
            db.passageDao(), afterFindRefsIn, releaseAfterFindRefsIn,
        )
        val runner = PassThroughRunner()

        val resultDef = CompletableDeferred<NwtRoomIndexIdentityResult>()
        launch {
            resultDef.complete(
                NwtRoomIndexIdentityAdapter.verify(
                    attachmentId = attachmentId,
                    canonicalRef = ref,
                    claim = claim(passageId = "p-18"),
                    attachmentDao = db.attachmentDao(),
                    passageDao = adapterDao,
                    transactionRunner = runner,
                )
            )
        }
        // Verificação pausada após findByRefsIn.
        withTimeout(30_000) { afterFindRefsIn.await() }

        // Escrita concorrente aplicável IMEDIATAMENTE (sem transação).
        val insertJob = launch {
            db.passageDao().insertAll(listOf(passage(id = "p-18-dup", text = text)))
        }
        withTimeout(30_000) { insertJob.join() }

        releaseAfterFindRefsIn.complete(Unit)
        val result = withTimeout(30_000) { resultDef.await() }

        // Controle negativo: o estado inconsistente EXPOSTO (não RECORD_MATCH).
        assertNotEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertEquals(NwtRoomIndexIdentityState.AMBIGUOUS_RECORD, result.state)
    }
}
