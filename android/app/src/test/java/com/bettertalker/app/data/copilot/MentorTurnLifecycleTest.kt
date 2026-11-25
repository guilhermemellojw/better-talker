package com.bettertalker.app.data.copilot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MENTOR-INTEGRATION-P14 — controlador efetivamente usado por send(). */
class MentorTurnLifecycleTest {

    private fun target() = CardTarget(
        kind = CardTargetKind.SECTION,
        id = "sec-1",
        label = "Ponto 1",
        baseHash = "h",
        capturedFrom = CardCapturedFrom.ACTIVE_BLOCK,
        capturedAt = 0L,
    )

    private suspend fun run(
        history: suspend () -> List<ChatTurn> = { listOf(ChatTurn(true, "pergunta")) },
        dossier: suspend () -> com.bettertalker.app.domain.planning.Dossier? = { null },
        target: suspend (com.bettertalker.app.domain.planning.Dossier?) -> CardTarget? = { target() },
        persist: suspend () -> Unit = {},
    ) = MentorTurnLifecycle.prepare(
        MentorTurnLifecycle.Operations(history, dossier, target, persist),
    )

    @Test
    fun t01_sucesso_persistePerguntaUmaVez() = runBlocking {
        val log = mutableListOf<String>()
        val r = run(
            history = { log += "history"; listOf(ChatTurn(true, "pergunta")) },
            dossier = { log += "dossier"; null },
            target = { log += "target"; target() },
            persist = { log += "post" },
        )
        assertTrue(r is MentorTurnLifecycle.Result.Ready)
        val p = (r as MentorTurnLifecycle.Result.Ready).prepared
        assertEquals(1, p.history.size)
        assertNull(p.historyFailure)
        assertEquals("sec-1", p.target?.id)
        assertEquals(listOf("history", "dossier", "target", "post"), log)
    }

    @Test
    fun t02_falhaHistorico_explicitaSemOcultar() = runBlocking {
        val log = mutableListOf<String>()
        val r = run(
            history = { log += "history"; throw IllegalStateException("histórico") },
            dossier = { log += "dossier"; null },
            target = { log += "target"; target() },
            persist = { log += "post" },
        )
        assertTrue(r is MentorTurnLifecycle.Result.Ready)
        val p = (r as MentorTurnLifecycle.Result.Ready).prepared
        assertTrue(p.history.isEmpty())
        assertTrue(p.historyFailure is IllegalStateException)
        assertEquals(listOf("history", "dossier", "target", "post"), log)
    }

    @Test
    fun t03_falhaDossiê_degradaSemInventar() = runBlocking {
        var dossierPassed: com.bettertalker.app.domain.planning.Dossier? = target()?.let { null }
        var persisted = 0
        val r = run(
            dossier = { throw IllegalStateException("dossiê") },
            target = { d -> dossierPassed = d; target() },
            persist = { persisted++ },
        )
        assertTrue(r is MentorTurnLifecycle.Result.Ready)
        assertNull(dossierPassed)
        assertEquals(1, persisted)
    }

    @Test
    fun t04_falhaCapturaAlvo_naoPersistePergunta() = runBlocking {
        var persisted = false
        val r = run(
            target = { throw IllegalArgumentException("alvo") },
            persist = { persisted = true },
        )
        assertTrue(r is MentorTurnLifecycle.Result.Failed)
        assertEquals(MentorTurnLifecycle.Stage.TARGET, (r as MentorTurnLifecycle.Result.Failed).stage)
        assertFalse(persisted)
    }

    @Test
    fun t05_falhaPersistencia_identificada() = runBlocking {
        val r = run(persist = { throw IllegalStateException("room") })
        assertTrue(r is MentorTurnLifecycle.Result.Failed)
        assertEquals(MentorTurnLifecycle.Stage.QUESTION, (r as MentorTurnLifecycle.Result.Failed).stage)
    }

    @Test
    fun t06_cancelamentoEmQualquerEstagio_propaga() = runBlocking {
        var p = false
        try { run(history = { throw CancellationException("histórico") }) }
        catch (e: CancellationException) { p = true }
        assertTrue(p)

        p = false
        try { run(dossier = { throw CancellationException("dossiê") }) }
        catch (e: CancellationException) { p = true }
        assertTrue(p)

        p = false
        try { run(target = { throw CancellationException("alvo") }) }
        catch (e: CancellationException) { p = true }
        assertTrue(p)

        p = false
        try { run(persist = { throw CancellationException("post") }) }
        catch (e: CancellationException) { p = true }
        assertTrue(p)
    }
}
