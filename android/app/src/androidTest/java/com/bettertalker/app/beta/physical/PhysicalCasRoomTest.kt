package com.bettertalker.app.beta.physical

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bettertalker.app.data.db.ChatEntity
import com.bettertalker.app.data.util.ChatCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BETA-PHYSICAL/P9 — CAS real no Room (arquivo), sem mocks.
 *
 * A barreira é determinística: o latch garante a edição antes do CAS; no
 * teste de dois escritores, ambos partem juntos e o SQLite serializa.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalCasRoomTest {

    private lateinit var fixture: PhysicalRoomFixture
    private val msgId = "p9-msg"

    @Before
    fun setUp() {
        fixture = PhysicalRoomFixture()
        fixture.open()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun updatedPayload(
        entity: ChatEntity,
        text: String,
        provenance: com.bettertalker.app.data.copilot.DeliveryProvenance,
    ): String = ChatCodec.escMap(
        ChatCodec.unescMap(entity.payload).toMutableMap().apply {
            put("text", text)
            putAll(provenance.toPayload())
        },
    )

    private suspend fun seedValid(text: String = "Conteúdo fundamentado inicial."): Pair<ChatEntity, com.bettertalker.app.data.copilot.DeliveryProvenance> {
        fixture.seedNote()
        val prov = fixture.validProvenance(msgId, text = text)
        val entity = fixture.seedMessage(msgId, text, prov)
        return entity to prov
    }

    @Test
    fun casLegitimoAtualizaPayloadEProveniencia() = runBlocking {
        val (entity, prov) = seedValid()
        val expected = entity.payload
        val newText = "Conteúdo fundamentado inicial. Continuação válida."
        val continued = prov.continueFrom("Conteúdo fundamentado inicial.", newText, 2L)
        assertNotNull(continued)
        val newPayload = updatedPayload(entity, newText, continued!!)

        val written = fixture.db.chatDao().updateMessagePayloadIfUnchanged(msgId, expected, newPayload)

        assertEquals(1, written)
        val row = fixture.message(msgId)
        assertEquals(newText, fixture.textOf(row))
        val restored = fixture.provenanceOf(row)
        assertNotNull(restored)
        assertTrue(restored!!.binds(newText))
        assertFalse(restored.binds("Conteúdo fundamentado inicial."))
        assertTrue(restored.authorizesMessage(PhysicalRoomFixture.NOTE_ID, newText))
    }

    @Test
    fun casAposEdicaoConcorrenteNaoSobrescreve() = runBlocking {
        val (entity, _) = seedValid()
        val expected = entity.payload
        val newPayload = updatedPayload(
            entity,
            "tentativa de continuação",
            fixture.validProvenance(msgId, text = "tentativa de continuação"),
        )
        val editDone = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val edit = pool.submit {
                runBlocking {
                    fixture.db.chatDao().updateMessagePayload(
                        msgId,
                        fixture.invalidatedPayload(entity, "edição concorrente"),
                    )
                    editDone.countDown()
                }
            }
            assertTrue("edição não concluiu", editDone.await(5, TimeUnit.SECONDS))
            edit.get(5, TimeUnit.SECONDS)

            val written = fixture.db.chatDao().updateMessagePayloadIfUnchanged(msgId, expected, newPayload)

            assertEquals(0, written)
            assertEquals("edição concorrente", fixture.textOf(fixture.message(msgId)))
            assertNull(fixture.provenanceOf(fixture.message(msgId)))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun casAposExclusaoNaoAtualiza() = runBlocking {
        val (entity, _) = seedValid()
        val expected = entity.payload
        fixture.db.chatDao().delete(msgId)

        val written = fixture.db.chatDao().updateMessagePayloadIfUnchanged(
            msgId,
            expected,
            updatedPayload(entity, "não deve entrar", fixture.validProvenance(msgId, text = "não deve entrar")),
        )

        assertEquals(0, written)
        assertNull(fixture.message(msgId))
    }

    @Test
    fun casAposDesativacaoNaoAtualiza() = runBlocking {
        val (entity, _) = seedValid()
        val expected = entity.payload
        fixture.db.chatDao().deactivateMessage(msgId)

        val written = fixture.db.chatDao().updateMessagePayloadIfUnchanged(
            msgId,
            expected,
            updatedPayload(entity, "não deve entrar", fixture.validProvenance(msgId, text = "não deve entrar")),
        )

        assertEquals(0, written)
        val row = fixture.message(msgId)
        assertNotNull(row)
        assertFalse(row!!.isActive)
        assertEquals("Conteúdo fundamentado inicial.", fixture.textOf(row))
    }

    @Test
    fun casAposInvalidacaoDeProvenienciaNaoAtualiza() = runBlocking {
        val (entity, _) = seedValid()
        val expected = entity.payload
        fixture.db.chatDao().updateMessagePayload(
            msgId,
            fixture.invalidatedPayload(entity, "proveniência invalidada"),
        )

        val written = fixture.db.chatDao().updateMessagePayloadIfUnchanged(
            msgId,
            expected,
            updatedPayload(entity, "não deve entrar", fixture.validProvenance(msgId, text = "não deve entrar")),
        )

        assertEquals(0, written)
        val row = fixture.message(msgId)
        assertEquals("proveniência invalidada", fixture.textOf(row))
        assertNull(fixture.provenanceOf(row))
    }

    @Test
    fun doisEscritoresCasApenasUmVence() = runBlocking {
        val (entity, _) = seedValid()
        val expected = entity.payload
        val textA = "escritor A"
        val textB = "escritor B"
        val payloadA = updatedPayload(entity, textA, fixture.validProvenance(msgId, text = textA))
        val payloadB = updatedPayload(entity, textB, fixture.validProvenance(msgId, text = textB))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Int> {
                start.await(5, TimeUnit.SECONDS)
                runBlocking { fixture.db.chatDao().updateMessagePayloadIfUnchanged(msgId, expected, payloadA) }
            }
            val b = pool.submit<Int> {
                start.await(5, TimeUnit.SECONDS)
                runBlocking { fixture.db.chatDao().updateMessagePayloadIfUnchanged(msgId, expected, payloadB) }
            }
            start.countDown()
            val results = listOf(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))

            assertEquals("exatamente um CAS deve vencer", 1, results.count { it == 1 })
            val row = fixture.message(msgId)
            val text = fixture.textOf(row)
            assertTrue("payload vencedor deve ser A ou B", text == textA || text == textB)
            assertNotNull(fixture.provenanceOf(row))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun casPersisteAposFecharEReabrirArquivo() = runBlocking {
        val (entity, prov) = seedValid()
        fixture.reopen()
        val reopened = fixture.message(msgId)
        assertNotNull(reopened)
        assertEquals("Conteúdo fundamentado inicial.", fixture.textOf(reopened))
        assertNotNull(fixture.provenanceOf(reopened))
        val expected = reopened!!.payload
        val newText = "Continuação após reabertura."
        val continued = prov.continueFrom("Conteúdo fundamentado inicial.", newText, 2L)!!
        val newPayload = updatedPayload(reopened, newText, continued)

        val written = fixture.db.chatDao().updateMessagePayloadIfUnchanged(msgId, expected, newPayload)

        assertEquals(1, written)
        assertEquals(newText, fixture.textOf(fixture.message(msgId)))
        assertTrue(fixture.provenanceOf(fixture.message(msgId))!!.binds(newText))
    }

    @Test
    fun casComExpectedErradoNaoAtualiza() = runBlocking {
        val (entity, _) = seedValid()
        val written = fixture.db.chatDao().updateMessagePayloadIfUnchanged(
            msgId,
            "{payload-que-nao-existe}",
            updatedPayload(entity, "não deve entrar", fixture.validProvenance(msgId, text = "não deve entrar")),
        )
        assertEquals(0, written)
        assertEquals("Conteúdo fundamentado inicial.", fixture.textOf(fixture.message(msgId)))
    }
}
