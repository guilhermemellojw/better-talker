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
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.db.TransactionRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TRUST-2B3B-B1b — validação instrumentada da consistência transacional do
 * adaptador de identidade do índice Room (cenários 17–18).
 *
 * Usa banco Room REAL (in-memory) com DAOs reais e coordenação determinística
 * (CompletableDeferred — sem sleeps). O isolamento transacional é demonstrado
 * somente por testes com Room/SQLite real: a verificação observa uma visão
 * consistente quando o anexo/passagem é alterado concorrentemente durante a
 * execução (mutações serializadas após a transação).
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

    /** DAO que sinaliza após o primeiro `get` retornar (dentro da transação). */
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

    /** DAO que sinaliza após `findByRefsIn` retornar (dentro da transação). */
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

    /**
     * Runner que mantém a transação aberta (barreira suspensiva controlada)
     * enquanto o teste confirma que a escrita concorrente foi iniciada —
     * sem depender de `Thread.sleep`.
     */
    private class GatedTransactionRunner(
        private val db: AppDatabase,
        private val releaseTx: CompletableDeferred<Unit>,
    ) : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R =
            db.withTransaction {
                val result = block()
                releaseTx.await() // transação aberta enquanto o teste inicia a escrita
                result
            }
    }

    /** DAO que sinaliza o início de `delete` antes de delegar. */
    private class DeletingAttachmentDao(
        private val delegate: AttachmentDao,
        private val deleteStarted: CompletableDeferred<Unit>,
    ) : AttachmentDao by delegate {
        override suspend fun delete(id: String) {
            deleteStarted.complete(Unit)
            delegate.delete(id)
        }
    }

    /** DAO que sinaliza o início de `insertAll` antes de delegar. */
    private class SignalingUpdateDao(
        private val delegate: PassageDao,
        private val updateStarted: CompletableDeferred<Unit>,
    ) : PassageDao by delegate {
        override suspend fun insertAll(items: List<PassageEntity>) {
            updateStarted.complete(Unit)
            delegate.insertAll(items)
        }
    }

    // ---------- 17. Exclusão concorrente do anexo ----------
    @Test
    fun cenario17_exclusaoConcorrenteDoAnexoObservaEstadoConsistente() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-17")))
        val readOneDone = CompletableDeferred<Unit>()
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseTx = CompletableDeferred<Unit>()
        val adapterDao = SignalingAttachmentDao(db.attachmentDao(), readOneDone)
        val gatedRunner = GatedTransactionRunner(db, releaseTx)

        // 1. Inicia a verificação dentro do RoomTransactionRunner com a
        //    barreira que mantém a transação aberta.
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
        // 3. Primeira leitura concluída — a transação está aberta
        //    (bloqueada na barreira, com o anexo e passagens lidos).
        withTimeout(30_000) { readOneDone.await() }

        // 5. Inicia a exclusão concorrente do anexo (escrita observável).
        val deletingDao = DeletingAttachmentDao(db.attachmentDao(), deleteStarted)
        val deleteJob = launch {
            deletingDao.delete(attachmentId)
            db.passageDao().deleteForAttachment(attachmentId)
        }
        // 6. Confirma que a tentativa de escrita foi iniciada
        //    (CompletableDeferred — sem sleep, sem runCatching).
        withTimeout(30_000) { deleteStarted.await() }

        // 7. Libera a verificação — a transação commita.
        releaseTx.complete(Unit)
        // 8. Aguarda a verificação e a exclusão.
        val result = withTimeout(30_000) { resultDef.await() }
        withTimeout(30_000) { verifyJob.join() }
        withTimeout(30_000) { deleteJob.join() }

        // 9. Resultado consistente da verificação.
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertNotNull(result.unit)
        assertEquals(attachmentId, result.unit!!.attachmentId)
        assertEquals("p-17", result.unit.passageId)
        assertEquals(text, result.unit.text)
        // 10. Efeito final observável da exclusão (a exclusão deve ser
        //     observavelmente bem-sucedida — caso contrário o assertNull falha).
        assertNull(db.attachmentDao().get(attachmentId))
    }

    // ---------- 18. Alteração concorrente da passagem ----------
    @Test
    fun cenario18_alteracaoConcorrenteDaPassagemNaoCertificaUnidadeIncompativel() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-18", text = text)))
        val readTwo = CompletableDeferred<Unit>()
        val updateStarted = CompletableDeferred<Unit>()
        val releaseTx = CompletableDeferred<Unit>()
        val adapterDao = SignalingPassageDao(db.passageDao(), readTwo)
        val gatedRunner = GatedTransactionRunner(db, releaseTx)

        // 2. Inicia a verificação transacional.
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
        // 3. Pausa depois de uma leitura relevante — a transação está
        //    aberta (bloqueada na barreira).
        withTimeout(30_000) { readTwo.await() }

        // 4. Inicia a escrita concorrente com texto diferente.
        val newText = "Texto alterado (T2)."
        val updateDao = SignalingUpdateDao(db.passageDao(), updateStarted)
        val updateJob = launch {
            updateDao.insertAll(listOf(passage(id = "p-18", text = newText)))
        }
        // Iniciação confirmada da escrita (sem sleep).
        withTimeout(30_000) { updateStarted.await() }

        // 5. Libera a transação.
        releaseTx.complete(Unit)

        // 6. Confirma que a unidade verificada corresponde a uma visão
        //    consistente: RECORD_MATCH com o texto original.
        val result = withTimeout(30_000) { resultDef.await() }
        withTimeout(30_000) { verifyJob.join() }
        // 7. Confirma que a atualização concorrente foi efetivamente
        //    persistida.
        withTimeout(30_000) { updateJob.join() }
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertNotNull(result.unit)
        // Verificação negativa controlada: o resultado certifica a unidade
        // formada pelas leituras da transação-tempo — `unit.text == text`
        // (original) — e a atualização foi persistida após a transação
        // concluir; sem o isolamento, o interleaving produziria o estado
        // incoerente que este teste detecta (as asserts falhariam).
        assertEquals(text, result.unit!!.text)
        assertEquals(newText, db.passageDao().findByRefIn(attachmentId, ref)?.text)
    }
}
