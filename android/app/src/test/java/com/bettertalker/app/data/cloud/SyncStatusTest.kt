package com.bettertalker.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * F5g (Discovery #3) — estado volátil do sync: inicial Idle e transições
 * Running → Success/Error.
 */
class SyncStatusTest {

    @Before
    fun reset() {
        SyncStatus.set(SyncStatus.State.Idle)
    }

    @Test
    fun estadoInicial_idle() {
        assertEquals(SyncStatus.State.Idle, SyncStatus.state.value)
    }

    @Test
    fun transicao_running_success() {
        SyncStatus.set(SyncStatus.State.Running)
        assertEquals(SyncStatus.State.Running, SyncStatus.state.value)

        SyncStatus.set(SyncStatus.State.Success(1234L))
        assertEquals(SyncStatus.State.Success(1234L), SyncStatus.state.value)
    }

    @Test
    fun transicao_running_error() {
        SyncStatus.set(SyncStatus.State.Running)
        SyncStatus.set(SyncStatus.State.Error("permission denied", 5678L))
        assertEquals(SyncStatus.State.Error("permission denied", 5678L), SyncStatus.state.value)
    }
}
