package com.bettertalker.app.data.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBackupPolicyTest {

    @Test
    fun gifPequenoMantemAnimacao() {
        assertTrue(
            ImageBackupPolicy.keepOriginal("image/gif", 2L * 1024 * 1024)
        )
        assertEquals(
            "gif",
            ImageBackupPolicy.extensionFor("image/gif", 2L * 1024 * 1024),
        )
        assertEquals(
            "image/gif",
            ImageBackupPolicy.contentTypeFor("image/gif", 2L * 1024 * 1024),
        )
    }

    @Test
    fun gifGrandeViraWebpEstatico() {
        val big = 2L * 1024 * 1024 + 1
        assertFalse(ImageBackupPolicy.keepOriginal("image/gif", big))
        assertEquals("webp", ImageBackupPolicy.extensionFor("image/gif", big))
        assertEquals(
            "image/webp",
            ImageBackupPolicy.contentTypeFor("image/gif", big),
        )
    }

    @Test
    fun webpPequenoNaoEhRecomprimido() {
        assertTrue(
            ImageBackupPolicy.keepOriginal("image/webp", 900L * 1024)
        )
        assertEquals(
            "webp",
            ImageBackupPolicy.extensionFor("image/webp", 900L * 1024),
        )
        assertFalse(
            ImageBackupPolicy.keepOriginal("image/jpeg", 900L * 1024)
        )
    }

    @Test
    fun limiteDeOrigemEhDezMegabytes() {
        assertTrue(ImageBackupPolicy.isUploadable("image/jpeg", 10L * 1024 * 1024))
        assertFalse(
            ImageBackupPolicy.isUploadable("image/jpeg", 10L * 1024 * 1024 + 1)
        )
        assertFalse(ImageBackupPolicy.isUploadable("application/pdf", 1))
    }

    @Test
    fun caminhoRemotoEhUidScoped() {
        assertEquals(
            "users/uid-1/images/note-2/img-3.webp",
            ImageBackupPolicy.remotePath("uid-1", "note-2", "img-3", "webp"),
        )
    }

    @Test
    fun orcamentoAvisaEmOitocentosMegabytes() {
        assertNull(ImageBackupPolicy.budgetWarning(799L * 1024 * 1024))
        assertTrue(
            ImageBackupPolicy.budgetWarning(800L * 1024 * 1024)!!.contains("800 MB")
        )
        assertTrue(
            ImageBackupPolicy.budgetWarning(1024L * 1024 * 1024)!!.contains("1 GB")
        )
    }

    @Test
    fun statsDerivamPendentesEOrcamento() {
        val stats = ImageBackupStats(
            total = 10,
            uploaded = 7,
            failed = 1,
            uploadedBytes = 900L * 1024 * 1024,
        )
        assertEquals(3, stats.pending)
        assertTrue(stats.budgetWarning!!.contains("800 MB"))
    }
}
