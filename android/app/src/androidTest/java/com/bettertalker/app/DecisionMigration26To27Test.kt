package com.bettertalker.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.repo.DecisionMemoryRepository
import com.bettertalker.app.domain.speech.SectionRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1C.2.1A2 — upgrade REAL de um banco v26 para v27.
 *
 * A fixture é criada do zero com o DDL exato do AppDatabase v26 (16 tabelas)
 * e dados de nota/seção/sub-ponto. Depois o Room v27 abre o arquivo com a
 * MESMA [DbProvider.MIGRATION_26_27] de produção. Nenhum banco de usuário é
 * tocado.
 */
@RunWith(AndroidJUnit4::class)
class DecisionMigration26To27Test {

    private companion object {
        const val DB_NAME = "decision-migration-26-27-test.db"
    }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: DecisionMemoryRepository

    @Before
    fun createV26Fixture() {
        ctx.deleteDatabase(DB_NAME)
        val path = ctx.getDatabasePath(DB_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { raw ->
            V26Schema.DDL.forEach { raw.execSQL(it) }
            raw.execSQL(
                "INSERT INTO notes (" +
                    "id,title,mdText,plainText,folderId,colorArgb,pinned,trashed," +
                    "createdAt,updatedAt,richHtml,discourseType,lastSyncedAt" +
                    ") VALUES (" +
                    "'n1','Nota v26','# texto','texto',NULL,0,0,0,10,10," +
                    "'','S34_DISCOURSE',0)"
            )
            raw.execSQL(
                "INSERT INTO speech_sections (" +
                    "id,noteId,`order`,role,title,minutes,contentHtml," +
                    "bibleRefsJson,publicationRefsJson,methodPrinciple,objective," +
                    "agreedApproach,approvedAt,tribunaJson,createdAt,updatedAt" +
                    ") VALUES (" +
                    "'s1','n1',1,'BODY','Ponto v26',5,'<p>conteúdo</p>'," +
                    "'[]','[]',NULL,NULL,NULL,NULL,NULL,10,10)"
            )
            raw.execSQL(
                "INSERT INTO sub_points (" +
                    "id,sectionId,`order`,outlineText,bibleRefsJson," +
                    "publicationRefsJson,instruction,developedHtml,createdAt,updatedAt" +
                    ") VALUES (" +
                    "'sp1','s1',1,'Sub-ponto v26','[]','[]',NULL,'<p>sub</p>',10,10)"
            )
            raw.version = 26
        }
    }

    private fun openV27() {
        db = Room.databaseBuilder(ctx, AppDatabase::class.java, DB_NAME)
            .addMigrations(DbProvider.MIGRATION_26_27)
            .allowMainThreadQueries()
            .build()
        // Força o upgrade 26 -> 27 e a validação de schema do Room.
        db.openHelper.writableDatabase
        repo = DecisionMemoryRepository(db.decisionDao(), RoomTransactionRunner(db))
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        ctx.deleteDatabase(DB_NAME)
    }

    private fun intQuery(sql: String): Int =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "consulta sem linha: $sql" }
            cursor.getInt(0)
        }

    @Test
    fun upgradeAbreComRoomEPreservaDadosExistentes() = runBlocking {
        openV27()

        assertEquals(27, intQuery("PRAGMA user_version"))
        assertEquals(
            1,
            intQuery(
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE type='table' AND name='decision_records'"
            ),
        )
        assertEquals(0, intQuery("SELECT COUNT(*) FROM decision_records"))

        assertEquals("Nota v26", db.noteDao().get("n1")?.title)
        assertEquals("Ponto v26", db.speechSectionDao().get("s1")?.title)
        assertEquals("Sub-ponto v26", db.subPointDao().get("sp1")?.outlineText)
    }

    @Test
    fun upgradeMantemPoliticasDeExclusaoNoBancoMigrado() = runBlocking {
        openV27()
        val d = DecisionRecord(
            id = "d1",
            noteId = "n1",
            scope = DecisionScope.SECTION,
            kind = DecisionKind.CONFIRMED,
            decisionState = DecisionState.ACTIVE,
            sectionLink = SectionLink.VALID,
            text = "usar contraste",
            source = DecisionSource.EXPLICIT_USER,
            sectionId = "s1",
            originalSectionId = "s1",
            sectionTitleSnapshot = "Ponto v26",
            sectionRoleSnapshot = SectionRole.BODY,
            sectionOrderSnapshot = 1,
            sectionContentHashSnapshot = "hash-v26",
            originalSectionTitleSnapshot = "Ponto v26",
            originalSectionRoleSnapshot = SectionRole.BODY,
            originalSectionOrderSnapshot = 1,
            originalSectionContentHashSnapshot = "hash-v26",
            createdAt = 10L,
            updatedAt = 10L,
        )
        repo.save(d)
        assertEquals(d, repo.get("d1"))

        // Excluir a seção NÃO pode apagar a decisão (sectionId é lógico).
        db.speechSectionDao().delete(db.speechSectionDao().get("s1")!!)
        assertNotNull(repo.get("d1"))
        assertEquals("s1", repo.get("d1")!!.sectionId)

        // Excluir a nota remove a decisão em cascata (política de noteId).
        db.noteDao().delete("n1")
        assertNull(repo.get("d1"))
        assertEquals(0, intQuery("SELECT COUNT(*) FROM decision_records"))
    }

    @Test
    fun upgradeNaoFazBackfillDeDecisoes() {
        openV27()
        assertEquals(0, intQuery("SELECT COUNT(*) FROM decision_records"))
        assertEquals(1, intQuery("SELECT COUNT(*) FROM notes"))
        assertEquals(1, intQuery("SELECT COUNT(*) FROM speech_sections"))
        assertEquals(1, intQuery("SELECT COUNT(*) FROM sub_points"))
    }
}
