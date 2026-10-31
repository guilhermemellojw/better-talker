package com.bettertalker.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.data.backup.BackupAttachment
import com.bettertalker.app.data.backup.BackupCodec
import com.bettertalker.app.data.backup.BackupManifest
import com.bettertalker.app.data.backup.BackupPreview
import com.bettertalker.app.data.backup.BackupRepository
import com.bettertalker.app.data.backup.BackupSnapshot
import com.bettertalker.app.data.db.AppDatabase
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Import real sobre Room in-memory: ordem, merge de anexo e rollback.
 * Não toca no banco do usuário (o teste roda no pacote debug, com DB próprio).
 */
@RunWith(AndroidJUnit4::class)
class BackupRepositoryInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: BackupRepository

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BackupRepository(ctx, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun importInsereTudoEImportaMetadadosDoAnexo() = runBlocking {
        val snapshot = snapshot()
        repo.import(preview(snapshot))

        assertEquals("Nota 192", db.noteDao().get("n1")?.title)
        assertEquals("Discursos", db.folderDao().all().single().name)
        assertEquals(1, db.speechSectionDao().forNote("n1").size)
        assertEquals(1, db.subPointDao().forSection("s1").size)
        assertEquals(1, db.outlineDao().all().size)

        val attachment = db.attachmentDao().get("a1")
        assertNotNull(attachment)
        assertEquals("failed", attachment!!.status)
        assertEquals("", attachment.appPath)
        assertEquals("https://example/w_T_20111215.jwpub", attachment.sourceUrl)

        assertEquals("S-34", db.s34Dao().outlineById("s34o1")?.title)
        assertEquals(1, db.s34Dao().sectionsOf("s34o1").size)
        assertEquals(1, db.s34Dao().subsectionsOf(listOf("s34s1")).size)
        assertEquals(1, db.s34Dao().referencesOf("s34o1").size)
        assertEquals(1, db.tombstoneDao().all().size)
    }

    @Test
    fun falhaNoMeioDoImportNaoDeixaNotaParcial() = runBlocking {
        // Subponto órfão: a FK de sub_points → speech_sections deve falhar
        // depois de a nota já ter sido inserida; a transação precisa voltar.
        val bad = snapshot().copy(
            subPoints = listOf(
                snapshot().subPoints.single().copy(sectionId = "secao-ausente"),
            ),
        )
        val error = runCatching { repo.import(preview(bad)) }.exceptionOrNull()
        assertNotNull("o import deveria falhar", error)
        assertNull("a nota não pode ficar parcial", db.noteDao().get("n1"))
        assertTrue(db.folderDao().all().isEmpty())
    }

    private fun preview(snapshot: BackupSnapshot): BackupPreview = BackupPreview(
        manifest = BackupManifest(
            format = BackupCodec.FORMAT,
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            appVersion = "test",
            versionCode = 1,
            dbVersion = 20,
            exportedAt = 1L,
            counts = snapshot.counts(),
            dataSha256 = "0".repeat(64),
        ),
        snapshot = snapshot,
    )

    private fun section(id: String, order: Int) = SpeechSectionEntity(
        id = id, noteId = "n1", order = order, role = "BODY", title = "Ponto",
        minutes = 5, contentHtml = "<p>x</p>", bibleRefsJson = "[]",
        publicationRefsJson = "[]", methodPrinciple = null, objective = null,
        agreedApproach = null, approvedAt = null, tribunaJson = null,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun snapshot(): BackupSnapshot = BackupSnapshot(
        folders = listOf(FolderEntity("f1", "Discursos", 1L, 1L)),
        notes = listOf(
            NoteEntity(
                id = "n1", title = "Nota 192", mdText = "# 192", plainText = "192",
                folderId = "f1", colorArgb = 2L, pinned = false, trashed = false,
                createdAt = 1L, updatedAt = 2L, richHtml = "",
            ),
        ),
        attachments = listOf(
            BackupAttachment(
                id = "a1", noteId = "n1", fileName = "w_T_20111215.jwpub",
                kind = "jwpub", sizeBytes = 100, baseSlot = null,
                sourceUrl = "https://example/w_T_20111215.jwpub",
                sourceType = "content", symbol = "w", addedAt = 1L,
            ),
        ),
        outlines = listOf(
            OutlineEntity(
                id = "o1", noteId = "n1", fileName = "S-34.docx", title = "Esboço",
                totalMinutes = 5, sectionsJson = "[]", refsJson = "[]",
                createdAt = 1L, updatedAt = 1L,
            ),
        ),
        speechSections = listOf(section("s1", 0)),
        subPoints = listOf(
            SubPointEntity(
                id = "sp1", sectionId = "s1", order = 0, outlineText = "sub",
                bibleRefsJson = "[]", publicationRefsJson = "[]", instruction = null,
                developedHtml = "<p>sub</p>", createdAt = 1L, updatedAt = 1L,
            ),
        ),
        s34Outlines = listOf(
            S34OutlineEntity(
                id = "s34o1", sourceAttachmentId = "a1", symbol = "w",
                title = "S-34", objective = null, headerLinesJson = "[]",
                createdAt = 1L, updatedAt = 1L, parserVersion = 1,
            ),
        ),
        s34Sections = listOf(
            S34SectionEntity(
                id = "s34s1", outlineId = "s34o1", order = 0, title = "Seção",
                content = "conteúdo", minutes = 5, sourceLine = 1,
            ),
        ),
        s34Subsections = listOf(
            S34SubsectionEntity(
                id = "s34ss1", sectionId = "s34s1", order = 0,
                content = "subseção", sourceLine = 2,
            ),
        ),
        s34References = listOf(
            S34ReferenceEntity(
                id = "s34r1", outlineId = "s34o1", sectionId = "s34s1",
                subsectionId = "s34ss1", order = 0, type = "PUBLICATION",
                rawText = "w11 15/12", normalizedReference = "w11 15/12",
                sourceLine = 3, book = null, bookNorm = null, chapter = null,
                verse = null, pubKind = "MAGAZINE", pubKey = "w",
                pubLabel = "A Sentinela", editionKey = "w|2011|12|15",
            ),
        ),
        tombstones = listOf(TombstoneEntity("t1", "note", 1L)),
    )
}
