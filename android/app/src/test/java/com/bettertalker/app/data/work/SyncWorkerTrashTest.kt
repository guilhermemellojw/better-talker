package com.bettertalker.app.data.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proteção do pull contra contaminação da nuvem: remoto nunca lixa
 * local vivo. Puro, sem Firebase/Room.
 */
class SyncWorkerTrashTest {

    @Test
    fun remoteCanTrashLocal_naoTrashaRemoto_retornaTrue() {
        assertTrue(remoteCanTrashLocal(localTrashed = false, remoteTrashed = false))
    }

    @Test
    fun remoteCanTrashLocal_lixaRemotoLocalVivo_retornaFalse() {
        // O caso do incidente (01/10 e 02/10/2026).
        assertFalse(remoteCanTrashLocal(localTrashed = false, remoteTrashed = true))
    }

    @Test
    fun remoteCanTrashLocal_ambosTrashed_retornaTrue() {
        assertTrue(remoteCanTrashLocal(localTrashed = true, remoteTrashed = true))
    }

    @Test
    fun remoteCanTrashLocal_restauracaoRemota_retornaTrue() {
        assertTrue(remoteCanTrashLocal(localTrashed = true, remoteTrashed = false))
    }
}
