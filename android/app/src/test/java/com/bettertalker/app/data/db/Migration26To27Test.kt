package com.bettertalker.app.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * 1C.2.1A2 — executa o SQL REAL da MIGRATION_26_27 contra SQLite.
 *
 * Cobre a política de FK: `noteId` -> notes CASCADE; `sectionId` é lógico,
 * sem FK para speech_sections. Também confirma que a migração é aditiva,
 * não faz backfill e pode ser reexecutada.
 */
class Migration26To27Test {

    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute(
                "CREATE TABLE notes (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "trashed INTEGER NOT NULL DEFAULT 0)"
            )
            st.execute("CREATE TABLE speech_sections (id TEXT NOT NULL PRIMARY KEY)")
            st.execute("INSERT INTO notes (id, trashed) VALUES ('n1', 0)")
            st.execute("INSERT INTO speech_sections (id) VALUES ('s1')")
        }
    }

    @After
    fun close() {
        conn.close()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_26_27) st.execute(sql)
        }
    }

    private fun single(sql: String): String? =
        conn.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            if (rs.next()) rs.getString(1) else null
        }

    private fun insertDecision(id: String = "d1") {
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO decision_records VALUES (" +
                    "'$id','n1','SECTION','CONFIRMED','ACTIVE','VALID','NOT_APPLIED'," +
                    "'usar contraste','EXPLICIT_USER','s1','s1',NULL," +
                    "'Ponto B','BODY',2,'hash-b','Ponto A','BODY',1,'hash-a'," +
                    "NULL,NULL,NULL,NULL,NULL,NULL,10,10,NULL)"
            )
        }
    }

    @Test
    fun tabelaEIndicesSaoCriados() {
        migrate()
        assertEquals(
            "decision_records",
            single(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='decision_records'"
            ),
        )
        assertEquals(
            "3",
            single(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN (" +
                    "'index_decision_records_noteId'," +
                    "'index_decision_records_noteId_sectionId'," +
                    "'index_decision_records_noteId_decisionState')"
            ),
        )
    }

    @Test
    fun colunasFicamNaOrdemDoEntity() {
        migrate()
        val columns = mutableListOf<String>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("PRAGMA table_info(decision_records)")
            while (rs.next()) columns += rs.getString("name")
        }
        assertEquals(
            listOf(
                "id", "noteId", "scope", "kind", "decisionState", "sectionLink",
                "applicationState", "text", "source", "sectionId", "originalSectionId",
                "candidateSectionId", "sectionTitleSnapshot", "sectionRoleSnapshot",
                "sectionOrderSnapshot", "sectionContentHashSnapshot",
                "originalSectionTitleSnapshot", "originalSectionRoleSnapshot",
                "originalSectionOrderSnapshot", "originalSectionContentHashSnapshot",
                "subjectKey", "sourceMessageId", "sourceProposalId", "supersedesId",
                "appliedAt", "appliedHash", "createdAt", "updatedAt", "removedAt",
            ),
            columns,
        )
    }

    @Test
    fun snapshotAtualEOriginalSaoPreservadosSeparadamente() {
        migrate()
        insertDecision()
        assertEquals(
            "Ponto B",
            single("SELECT sectionTitleSnapshot FROM decision_records WHERE id='d1'"),
        )
        assertEquals(
            "Ponto A",
            single("SELECT originalSectionTitleSnapshot FROM decision_records WHERE id='d1'"),
        )
        assertEquals(
            "2",
            single("SELECT sectionOrderSnapshot FROM decision_records WHERE id='d1'"),
        )
        assertEquals(
            "1",
            single("SELECT originalSectionOrderSnapshot FROM decision_records WHERE id='d1'"),
        )
    }

    @Test
    fun sectionIdNaoTemFkParaSpeechSections() {
        migrate()
        var fkCount = 0
        var noteFk = false
        conn.createStatement().use { st ->
            val rs = st.executeQuery("PRAGMA foreign_key_list(decision_records)")
            while (rs.next()) {
                fkCount++
                if (
                    rs.getString("from") == "noteId" &&
                    rs.getString("table") == "notes" &&
                    rs.getString("on_delete") == "CASCADE"
                ) {
                    noteFk = true
                }
            }
        }
        assertEquals("só a FK de noteId deve existir", 1, fkCount)
        assertTrue(noteFk)
    }

    @Test
    fun excluirSecaoNaoRemoveDecisao() {
        migrate()
        insertDecision()
        conn.createStatement().use { st ->
            st.execute("DELETE FROM speech_sections WHERE id='s1'")
        }
        assertEquals("1", single("SELECT COUNT(*) FROM decision_records"))
        assertEquals("s1", single("SELECT sectionId FROM decision_records WHERE id='d1'"))
    }

    @Test
    fun excluirNotaRemoveDecisoesEmCascata() {
        migrate()
        insertDecision()
        conn.createStatement().use { st ->
            st.execute("DELETE FROM notes WHERE id='n1'")
        }
        assertEquals("0", single("SELECT COUNT(*) FROM decision_records"))
    }

    @Test
    fun notaNaLixeiraPreservaDecisoes() {
        migrate()
        insertDecision()
        conn.createStatement().use { st ->
            st.execute("UPDATE notes SET trashed=1 WHERE id='n1'")
        }
        assertEquals("1", single("SELECT COUNT(*) FROM decision_records"))
    }

    @Test
    fun migracaoNaoFazBackfill() {
        migrate()
        assertEquals("0", single("SELECT COUNT(*) FROM decision_records"))
    }

    @Test
    fun migracaoPreservaDadosExistentes() {
        migrate()
        assertEquals("1", single("SELECT COUNT(*) FROM notes"))
        assertEquals("1", single("SELECT COUNT(*) FROM speech_sections"))
        assertEquals("0", single("SELECT trashed FROM notes WHERE id='n1'"))
    }

    @Test
    fun reexecucaoEhIdempotente() {
        migrate()
        insertDecision()
        migrate()
        assertEquals(
            "1",
            single(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='decision_records'"
            ),
        )
        assertEquals("1", single("SELECT COUNT(*) FROM decision_records"))
        assertNull(
            single(
                "SELECT name FROM sqlite_master WHERE name='decision_records_new'"
            ),
        )
    }

    @Test
    fun notaIdObrigatorioEhFkValida() {
        migrate()
        var failed = false
        try {
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO decision_records VALUES (" +
                        "'d2','nota-ausente','NOTE','PREFERENCE','ACTIVE','NOT_APPLICABLE'," +
                        "'NOT_APPLIED','tom pastoral','EXPLICIT_USER'," +
                        "NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL," +
                        "NULL,NULL,NULL,NULL,NULL,NULL,10,10,NULL)"
                )
            }
        } catch (_: SQLException) {
            failed = true
        }
        assertTrue("noteId inexistente deve violar a FK", failed)
    }
}
