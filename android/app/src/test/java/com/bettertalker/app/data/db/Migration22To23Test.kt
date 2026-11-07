package com.bettertalker.app.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * v4.2 — executa o SQL REAL da MIGRATION_22_23 contra SQLite, incluindo as
 * FKs (CASCADE ao excluir a nota; SET NULL ao excluir a seção).
 */
class Migration22To23Test {

    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("CREATE TABLE notes (id TEXT NOT NULL PRIMARY KEY)")
            st.execute(
                "CREATE TABLE speech_sections (" +
                    "id TEXT NOT NULL PRIMARY KEY, noteId TEXT NOT NULL)"
            )
            st.execute("INSERT INTO notes (id) VALUES ('n1')")
            st.execute("INSERT INTO speech_sections (id, noteId) VALUES ('s1', 'n1')")
        }
    }

    @After
    fun close() {
        conn.close()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_22_23) st.execute(sql)
        }
    }

    private fun single(sql: String): String? =
        conn.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            if (rs.next()) rs.getString(1) else null
        }

    @Test
    fun tabelaEIndicesSaoCriados() {
        migrate()
        assertEquals(
            "note_images",
            single("SELECT name FROM sqlite_master WHERE type='table' AND name='note_images'"),
        )
        assertEquals(
            "2",
            single(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' " +
                    "AND name IN ('index_note_images_noteId','index_note_images_sectionId')"
            ),
        )
    }

    @Test
    fun insereImagemDaNotaEDaSecao() {
        migrate()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO note_images VALUES " +
                    "('img-note','n1',NULL,'/x/a.jpg','image/jpeg','','cover',0,1)"
            )
            st.execute(
                "INSERT INTO note_images VALUES " +
                    "('img-sec','n1','s1','/x/b.png','image/png','Legenda','chart',1,2)"
            )
        }
        assertEquals("2", single("SELECT COUNT(*) FROM note_images"))
        assertEquals("", single("SELECT caption FROM note_images WHERE id='img-note'"))
    }

    @Test
    fun excluirSecaoMantemImagemComSectionIdNulo() {
        migrate()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO note_images VALUES " +
                    "('img-sec','n1','s1','/x/b.png','image/png','Legenda','chart',0,2)"
            )
            st.execute("DELETE FROM speech_sections WHERE id='s1'")
        }
        assertNull(single("SELECT sectionId FROM note_images WHERE id='img-sec'"))
        assertEquals("1", single("SELECT COUNT(*) FROM note_images"))
    }

    @Test
    fun excluirNotaApagaImagensEmCascata() {
        migrate()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO note_images VALUES " +
                    "('img-note','n1',NULL,'/x/a.jpg','image/jpeg','','cover',0,1)"
            )
            st.execute(
                "INSERT INTO note_images VALUES " +
                    "('img-sec','n1','s1','/x/b.png','image/png','Legenda','chart',1,2)"
            )
            st.execute("DELETE FROM notes WHERE id='n1'")
        }
        assertEquals("0", single("SELECT COUNT(*) FROM note_images"))
    }

    @Test
    fun reexecucaoDosIndicesEhIdempotente() {
        migrate()
        // CREATE TABLE/INDEX usam IF NOT EXISTS; reexecutar não duplica.
        migrate()
        assertEquals(
            "1",
            single(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='note_images'"
            ),
        )
        assertTrue(
            single("SELECT COUNT(*) FROM note_images") == "0"
        )
    }
}
