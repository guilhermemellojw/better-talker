package com.bettertalker.app

import com.bettertalker.app.data.ai.MODEL_MIN_FREE_BYTES
import com.bettertalker.app.data.ai.hasEnoughSpace
import com.bettertalker.app.data.ai.modelFileCandidates
import com.bettertalker.app.data.ai.sha256Hex
import com.bettertalker.app.data.ai.verifySha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T2 — helpers puros do downloader do modelo (JVM, sem Android). */
class ModelDownloadManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun shaVerifyAcceptsMatchingFileAndRejectsTamperedAndEmpty() {
        val f = tmp.newFile("modelo.bin")
        f.writeText("conteúdo do modelo")
        val hex = sha256Hex(f)
        assertEquals(64, hex.length)
        assertTrue(verifySha256(f, hex))
        assertTrue(verifySha256(f, hex.uppercase()))
        assertFalse(verifySha256(f, "0".repeat(64)))
        assertFalse(verifySha256(f, ""))
        f.appendText("!")
        assertFalse(verifySha256(f, hex))
    }

    @Test
    fun candidatesCoverInternalExternalAndPart() {
        val files = tmp.newFolder("files")
        val ext = tmp.newFolder("ext")
        val all = modelFileCandidates(files, ext)
        assertEquals(3, all.size)
        assertTrue(all[0].path.endsWith("files/models/gemma-4-E2B-it.litertlm"))
        assertTrue(all[1].path.endsWith("ext/gemma-4-E2B-it.litertlm"))
        assertTrue(all[2].path.endsWith("ext/gemma-4-E2B-it.litertlm.part"))
        val internalOnly = modelFileCandidates(files, null)
        assertEquals(1, internalOnly.size)
        assertTrue(internalOnly[0].path.endsWith("files/models/gemma-4-E2B-it.litertlm"))
    }

    @Test
    fun spaceGateRequires32Gb() {
        assertEquals(3_200_000_000L, MODEL_MIN_FREE_BYTES)
        assertFalse(hasEnoughSpace(2_588_147_712L))
        assertFalse(hasEnoughSpace(3_199_999_999L))
        assertTrue(hasEnoughSpace(3_200_000_000L))
    }
}
