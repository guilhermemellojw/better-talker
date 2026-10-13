package com.bettertalker.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F5g (Discovery #3) — transformação PURA do estado de sync para o texto do
 * indicador da tela Conta (o AccountViewModel não é JVM-testável: usa
 * Context/SettingsStore — o seam é esta função).
 */
class SyncIndicatorTextTest {

    private val now = 1_000_000_000_000L

    @Test
    fun idle() {
        assertEquals("Nunca sincronizado", syncIndicatorText(SyncStatus.State.Idle, now))
    }

    @Test
    fun running() {
        assertEquals("Sincronizando…", syncIndicatorText(SyncStatus.State.Running, now))
    }

    @Test
    fun success_agoraMesmo() {
        assertEquals(
            "Sincronizado agora mesmo",
            syncIndicatorText(SyncStatus.State.Success(now - 30_000L), now),
        )
    }

    @Test
    fun success_minutos() {
        assertEquals(
            "Sincronizado há 5 min",
            syncIndicatorText(SyncStatus.State.Success(now - 5L * 60_000L), now),
        )
    }

    @Test
    fun success_horas() {
        assertEquals(
            "Sincronizado há 2 h",
            syncIndicatorText(SyncStatus.State.Success(now - 2L * 60L * 60_000L), now),
        )
    }

    @Test
    fun success_dias() {
        assertEquals(
            "Sincronizado há 3 dia(s)",
            syncIndicatorText(SyncStatus.State.Success(now - 3L * 24L * 60L * 60_000L), now),
        )
    }

    @Test
    fun success_timestampFuturo_naoQuebra() {
        assertEquals(
            "Sincronizado agora mesmo",
            syncIndicatorText(SyncStatus.State.Success(now + 60_000L), now),
        )
    }

    @Test
    fun error_mostraMensagem() {
        assertEquals(
            "Erro no sync: PERMISSION_DENIED",
            syncIndicatorText(SyncStatus.State.Error("PERMISSION_DENIED", now), now),
        )
    }
}
