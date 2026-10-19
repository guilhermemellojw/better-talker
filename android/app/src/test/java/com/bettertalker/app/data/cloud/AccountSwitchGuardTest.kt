package com.bettertalker.app.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard mínimo de 1 conta por aparelho (sem ownerUid completo). */
class AccountSwitchGuardTest {

    @Test
    fun primeiraConta_naoBloqueia() {
        assertFalse(accountSwitchBlocked(lastUid = null, newUid = "uid-A"))
    }

    @Test
    fun mesmaConta_naoBloqueia() {
        assertFalse(accountSwitchBlocked(lastUid = "uid-A", newUid = "uid-A"))
    }

    @Test
    fun contaDiferente_bloqueia() {
        assertTrue(accountSwitchBlocked(lastUid = "uid-A", newUid = "uid-B"))
    }

    @Test
    fun semNovoUid_naoBloqueia() {
        assertFalse(accountSwitchBlocked(lastUid = "uid-A", newUid = null))
    }
}
