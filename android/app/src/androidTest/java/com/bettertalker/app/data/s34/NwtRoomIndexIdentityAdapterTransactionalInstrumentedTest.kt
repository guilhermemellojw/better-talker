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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    // ---------- 17. Exclusão concorrente do anexo ----------
    @Test
    fun cenario17_exclusaoConcorrenteDoAnexoObservaEstadoConsistente() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-17")))
        val readOne = CompletableDeferred<Unit>()
        val adapterDao = SignalingAttachmentDao(db.attachmentDao(), readOne)

        val resultDef = CompletableDeferred<NwtRoomIndexIdentityResult>()
        val verifyJob = launch {
            resultDef.complete(
                NwtRoomIndexIdentityAdapter.verify(
                    attachmentId = attachmentId,
                    canonicalRef = ref,
                    claim = claim(passageId = "p-17"),
                    attachmentDao = adapterDao,
                    passageDao = db.passageDao(),
                    transactionRunner = RoomTransactionRunner(db),
                )
            )
        }
        readOne.await() // primeira leitura concluída; verificação dentro da transação

        // Tenta-se remover o anexo enquanto a verificação continua.
        val deleteJob = launch {
            runCatching { db.attachmentDao().delete(attachmentId) }
            runCatching { db.passageDao().deleteForAttachment(attachmentId) }
        }

        val result = resultDef.await()
        verifyJob.join()
        deleteJob.join()

        // O verificador nunca combina metadados antigos com passagens
        // incompatíveis: observa o estado consistente da transação.
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertNotNull(result.unit)
        assertEquals(attachmentId, result.unit!!.attachmentId)
        assertEquals("p-17", result.unit.passageId)
        assertEquals(text, result.unit.text)
    }

    // ---------- 18. Alteração concorrente da passagem ----------
    @Test
    fun cenario18_alteracaoConcorrenteDaPassagemNaoCertificaUnidadeIncompativel() = runBlocking {
        db.attachmentDao().upsert(tnmAttachment())
        db.passageDao().insertAll(listOf(passage(id = "p-18", text = text)))
        val readTwo = CompletableDeferred<Unit>()
        val adapterDao = SignalingPassageDao(db.passageDao(), readTwo)

        val resultDef = CompletableDeferred<NwtRoomIndexIdentityResult>()
        val verifyJob = launch {
            resultDef.complete(
                NwtRoomIndexIdentityAdapter.verify(
                    attachmentId = attachmentId,
                    canonicalRef = ref,
                    claim = claim(passageId = "p-18"),
                    attachmentDao = db.attachmentDao(),
                    passageDao = adapterDao,
                    transactionRunner = RoomTransactionRunner(db),
                )
            )
        }
        readTwo.await() // segunda leitura concluída; verificação dentro da transação

        // Altera-se o texto da passagem enquanto a verificação continua
        // (insertAll com REPLACE substitui a linha pelo novo texto).
        val newText = "Texto alterado (T2)."
        val updateJob = launch {
            db.passageDao().insertAll(listOf(passage(id = "p-18", text = newText)))
        }

        val result = resultDef.await()
        verifyJob.join()
        updateJob.join()

        // O resultado certifica a unidade formada pelas leituras da
        // transação-tempo (texto original); a alteração prosseguiu após a
        // transação concluir.
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertNotNull(result.unit)
        assertEquals(text, result.unit!!.text)
        assertEquals(newText, db.passageDao().findByRefIn(attachmentId, ref)?.text)
    }
}
