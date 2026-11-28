package com.bettertalker.app.beta.physical

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.ui.copilot.CopilotViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * BETA-PHYSICAL/P9 — Room real + ViewModel real, com recriação via
 * ViewModelStore.clear() e reabertura do arquivo.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalViewModelRestartTest {

    private lateinit var fixture: PhysicalRoomFixture
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val noteId = PhysicalRoomFixture.NOTE_ID
    private val msgId = "p9-vm-msg"
    private val text = "Conteúdo fundamentado para cópia e inserção."

    @Before
    fun setUp() {
        fixture = PhysicalRoomFixture()
        fixture.open()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun newStore(): ViewModelStore = ViewModelStore()

    private fun createVm(store: ViewModelStore): CopilotViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CopilotViewModel(ctx, fixture.db, noteId) as T
        }
        return ViewModelProvider(store, factory)[CopilotViewModel::class.java]
    }

    private suspend fun awaitHydrated(vm: CopilotViewModel, id: String = msgId) {
        withTimeout(10_000) {
            vm.messages.first { list -> list.any { it.id == id } }
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 5_000, block: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!block()) delay(10)
        }
    }

    @Test
    fun provenienciaPersisteERecuperaAposReabrirArquivo() = runBlocking {
        fixture.seedNote()
        val prov = fixture.validProvenance(msgId, text = text)
        fixture.seedMessage(msgId, text, prov)

        fixture.reopen()

        val row = fixture.message(msgId)
        assertNotNull(row)
        assertEquals(text, fixture.textOf(row))
        val restored = fixture.provenanceOf(row)
        assertNotNull(restored)
        assertTrue(restored!!.binds(text))

        val store = newStore()
        try {
            val vm = createVm(store)
            awaitHydrated(vm)
            assertEquals(text, vm.eligibleCopyText(msgId, text))
            vm.insertEligibleTextIntoScope(msgId, text)
            assertEquals(text, vm.insertReq.value?.text)
        } finally {
            store.clear()
        }
    }

    @Test
    fun recriacaoDoViewModelMantemCopiaEInsercao() = runBlocking {
        fixture.seedNote()
        val prov = fixture.validProvenance(msgId, text = text)
        fixture.seedMessage(msgId, text, prov)

        val store1 = newStore()
        val vm1 = createVm(store1)
        awaitHydrated(vm1)
        assertEquals(text, vm1.eligibleCopyText(msgId, text))
        store1.clear()

        val store2 = newStore()
        try {
            val vm2 = createVm(store2)
            awaitHydrated(vm2)
            assertEquals(text, vm2.eligibleCopyText(msgId, text))
            vm2.insertEligibleTextIntoScope(msgId, text)
            assertEquals(text, vm2.insertReq.value?.text)
            val persisted = vm2.deliveryProvenanceFor(msgId)
            assertNotNull(persisted)
            assertTrue(persisted!!.binds(text))
        } finally {
            store2.clear()
        }
    }

    @Test
    fun rejeitadoNaoCopiaNemInsereAposRestart() = runBlocking {
        fixture.seedNote()
        val rejected = fixture.rejectedProvenance(msgId, text = text)
        fixture.seedMessage(msgId, text, rejected)

        fixture.reopen()

        val store = newStore()
        try {
            val vm = createVm(store)
            awaitHydrated(vm)
            assertEquals("", vm.eligibleCopyText(msgId, text))
            vm.insertEligibleTextIntoScope(msgId, text)
            assertNull(vm.insertReq.value)
        } finally {
            store.clear()
        }
    }

    @Test
    fun cacheAcompanhaInvalidacaoNoBanco() = runBlocking {
        fixture.seedNote()
        val prov = fixture.validProvenance(msgId, text = text)
        val entity = fixture.seedMessage(msgId, text, prov)

        val store = newStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = createVm(store)
            val collector = scope.launch { vm.messages.collect {} }
            try {
                awaitHydrated(vm)
                assertEquals(text, vm.eligibleCopyText(msgId, text))

                fixture.db.chatDao().updateMessagePayload(
                    msgId,
                    fixture.invalidatedPayload(entity, "inválido após edição"),
                )

                awaitCondition {
                    vm.eligibleCopyText(msgId, text).isEmpty() &&
                        vm.eligibleCopyText(msgId, "inválido após edição").isEmpty()
                }
                assertNull(fixture.provenanceOf(fixture.message(msgId)))
            } finally {
                collector.cancel()
            }
        } finally {
            scope.cancel()
            store.clear()
        }
    }

    @Test
    fun conteudoValidoSobreviveAoRestartSemPerda() = runBlocking {
        fixture.seedNote()
        val prov = fixture.validProvenance(msgId, text = text, boundAt = 10L)
        fixture.seedMessage(msgId, text, prov, createdAt = 10L)

        fixture.reopen()
        val before = fixture.message(msgId)
        assertNotNull(before)
        assertEquals(10L, before!!.createdAt)
        assertEquals(text, fixture.textOf(before))
        assertNotNull(fixture.provenanceOf(before))

        val store = newStore()
        try {
            val vm = createVm(store)
            awaitHydrated(vm)
            assertEquals(text, vm.eligibleCopyText(msgId, text))
            assertTrue(vm.historyTextFor(msgId, text, fromMe = false).contains("fundamentado"))
        } finally {
            store.clear()
        }
    }
}
