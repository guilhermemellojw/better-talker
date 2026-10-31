package com.bettertalker.app.data.backup

import com.bettertalker.app.data.db.FolderEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.OutlineEntity
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.domain.speech.DiscourseType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupCodecTest {

    private fun sampleSnapshot() = BackupSnapshot(
        folders = listOf(FolderEntity("f1", "Discursos", 1L, 10L)),
        notes = listOf(
            NoteEntity(
                id = "n1", title = "Nota 192", mdText = "# 192. Vida eterna",
                plainText = "192 Vida eterna", folderId = "f1", colorArgb = 2L,
                pinned = true, trashed = false, createdAt = 11L, updatedAt = 12L,
                richHtml = "<p>192</p>",
                discourseType = DiscourseType.S34_DISCOURSE.name,
            ),
        ),
        attachments = listOf(
            BackupAttachment(
                id = "a1", noteId = "n1", fileName = "w_T_20111215.jwpub",
                kind = "jwpub", sizeBytes = 305788, baseSlot = null,
                sourceUrl = "https://example/w_T_20111215.jwpub",
                sourceType = "content", symbol = "w", addedAt = 13L,
            ),
        ),
        outlines = listOf(
            OutlineEntity(
                id = "o1", noteId = "n1", fileName = "S-34.docx", title = "Esboço",
                totalMinutes = 5, sectionsJson = "[]", refsJson = "[]",
                createdAt = 14L, updatedAt = 15L,
            ),
        ),
        speechSections = listOf(
            SpeechSectionEntity(
                id = "s1", noteId = "n1", order = 0, role = "BODY", title = "Ponto",
                minutes = 5, contentHtml = "<p>corpo</p>", bibleRefsJson = "[]",
                publicationRefsJson = "[]", methodPrinciple = null, objective = "obj",
                agreedApproach = null, approvedAt = 16L, tribunaJson = null,
                createdAt = 17L, updatedAt = 18L,
            ),
        ),
        subPoints = listOf(
            SubPointEntity(
                id = "sp1", sectionId = "s1", order = 0, outlineText = "sub",
                bibleRefsJson = "[]", publicationRefsJson = "[]", instruction = null,
                developedHtml = "<p>sub</p>", createdAt = 19L, updatedAt = 20L,
            ),
        ),
        s34Outlines = listOf(
            S34OutlineEntity(
                id = "s34o1", sourceAttachmentId = "a1", symbol = "w",
                title = "S-34", objective = null, headerLinesJson = "[]",
                createdAt = 21L, updatedAt = 22L, parserVersion = 2,
            ),
        ),
        s34Sections = listOf(
            S34SectionEntity(
                id = "s34s1", outlineId = "s34o1", order = 0, title = "Seção",
                content = "conteúdo", minutes = 5, sourceLine = 3,
            ),
        ),
        s34Subsections = listOf(
            S34SubsectionEntity(
                id = "s34ss1", sectionId = "s34s1", order = 0,
                content = "subseção", sourceLine = 4,
            ),
        ),
        s34References = listOf(
            S34ReferenceEntity(
                id = "s34r1", outlineId = "s34o1", sectionId = "s34s1",
                subsectionId = "s34ss1", order = 0, type = "PUBLICATION",
                rawText = "w11 15/12", normalizedReference = "w11 15/12",
                sourceLine = 5, book = null, bookNorm = null, chapter = null,
                verse = null, pubKind = "MAGAZINE", pubKey = "w",
                pubLabel = "A Sentinela", editionKey = "w|2011|12|15",
            ),
        ),
        tombstones = listOf(TombstoneEntity("t1", "note", 23L)),
    )

    @Test
    fun roundTripPreservaDados() {
        val zip = BackupCodec.encodeZip(
            sampleSnapshot(), appVersion = "3.0.6", versionCode = 37,
            dbVersion = 20, exportedAt = 123L,
        )
        val preview = BackupCodec.readZip(ByteArrayInputStream(zip), currentDbVersion = 20)
        assertEquals("3.0.6", preview.manifest.appVersion)
        assertEquals(123L, preview.manifest.exportedAt)
        assertEquals(1, preview.snapshot.notes.size)
        assertEquals("Nota 192", preview.snapshot.notes.single().title)
        assertEquals("w_T_20111215.jwpub", preview.snapshot.attachments.single().fileName)
        assertEquals("Ponto", preview.snapshot.speechSections.single().title)
        assertEquals("sub", preview.snapshot.subPoints.single().outlineText)
        assertEquals("w|2011|12|15", preview.snapshot.s34References.single().editionKey)
        assertEquals(1, preview.manifest.counts.notes)
        assertEquals(1, preview.manifest.counts.s34References)
    }

    @Test
    fun checksumCorrompidoEhRejeitado() {
        val zip = BackupCodec.encodeZip(sampleSnapshot(), "3.0.6", 37, 20)
        val entries = unzip(zip)
        val data = entries.getValue(BackupCodec.DATA_ENTRY)
        entries[BackupCodec.DATA_ENTRY] = data + " ".toByteArray()
        val corrupted = zip(entries)
        val error = runCatching {
            BackupCodec.readZip(ByteArrayInputStream(corrupted), 20)
        }.exceptionOrNull()
        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message.orEmpty().contains("checksum"))
    }

    @Test
    fun contagemDivergenteEhRejeitada() {
        val zip = BackupCodec.encodeZip(sampleSnapshot(), "3.0.6", 37, 20)
        val entries = unzip(zip)
        val manifest = JSONObject(String(entries.getValue(BackupCodec.MANIFEST_ENTRY)))
        manifest.getJSONObject("counts").put("notes", 999)
        entries[BackupCodec.MANIFEST_ENTRY] = manifest.toString().toByteArray()
        val error = runCatching {
            BackupCodec.readZip(ByteArrayInputStream(zip(entries)), 20)
        }.exceptionOrNull()
        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message.orEmpty().contains("contagens"))
    }

    @Test
    fun schemaMaisNovoEhRejeitado() {
        val zip = BackupCodec.encodeZip(sampleSnapshot(), "3.0.6", 37, 20)
        val entries = unzip(zip)
        val manifest = JSONObject(String(entries.getValue(BackupCodec.MANIFEST_ENTRY)))
        manifest.put("schemaVersion", 999)
        entries[BackupCodec.MANIFEST_ENTRY] = manifest.toString().toByteArray()
        val error = runCatching {
            BackupCodec.readZip(ByteArrayInputStream(zip(entries)), 20)
        }.exceptionOrNull()
        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message.orEmpty().contains("schema"))
    }

    @Test
    fun referenciaAusenteEhRejeitada() {
        val snapshot = sampleSnapshot().copy(
            notes = listOf(
                sampleSnapshot().notes.single().copy(folderId = "nao-existe"),
            ),
        )
        val error = runCatching { BackupCodec.validateSnapshot(snapshot) }.exceptionOrNull()
        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message.orEmpty().contains("pasta ausente"))
    }

    @Test
    fun anexoApontandoParaNotaAusenteEhRejeitado() {
        val snapshot = sampleSnapshot().copy(
            attachments = listOf(
                sampleSnapshot().attachments.single().copy(noteId = "nao-existe"),
            ),
        )
        val error = runCatching { BackupCodec.validateSnapshot(snapshot) }.exceptionOrNull()
        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message.orEmpty().contains("nota ausente"))
    }

    private fun unzip(bytes: ByteArray): MutableMap<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
