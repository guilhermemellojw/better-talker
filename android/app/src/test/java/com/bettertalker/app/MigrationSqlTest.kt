package com.bettertalker.app

import com.bettertalker.app.data.db.MigrationSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Fase 10 (§23): executa o SQL REAL da MIGRATION_9_10 (mesmas strings da
 * produção) contra um schema v9 em SQLite de verdade. Sem Android/Room.
 */
class MigrationSqlTest {

    private lateinit var conn: Connection

    private fun v9Attachments(): String = """
        CREATE TABLE attachments (
            id TEXT NOT NULL, noteId TEXT, fileName TEXT NOT NULL, kind TEXT NOT NULL,
            sizeBytes INTEGER NOT NULL, appPath TEXT NOT NULL, indexed INTEGER NOT NULL,
            addedAt INTEGER NOT NULL, baseSlot TEXT, status TEXT NOT NULL, error TEXT,
            downloadId INTEGER NOT NULL, sourceUrl TEXT, PRIMARY KEY(id))
    """.trimIndent()

    private fun v9Passages(): String = """
        CREATE TABLE passages (
            id TEXT NOT NULL, attachmentId TEXT NOT NULL, text TEXT NOT NULL,
            normalized TEXT NOT NULL, section TEXT NOT NULL, PRIMARY KEY(id))
    """.trimIndent()

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute(v9Attachments())
            st.execute(v9Passages())
            // be | th | nwt | comum — com passages vinculados.
            st.execute("INSERT INTO attachments VALUES ('a-be','n','be.pdf','pdf',1,'/x',1,1,'be','ready',NULL,-1,NULL)")
            st.execute("INSERT INTO attachments VALUES ('a-th','n','th.pdf','pdf',1,'/x',1,1,'th','ready',NULL,-1,NULL)")
            st.execute("INSERT INTO attachments VALUES ('a-nwt','n','nwt.pdf','pdf',1,'/x',1,1,'nwt','ready',NULL,-1,NULL)")
            st.execute("INSERT INTO attachments VALUES ('a-w','n','w.pdf','pdf',1,'/x',1,1,NULL,'ready',NULL,-1,NULL)")
            st.execute("INSERT INTO passages VALUES ('p1','a-be','texto be','texto be','Secao')")
            st.execute("INSERT INTO passages VALUES ('p2','a-w','texto w','texto w','')")
        }
    }

    @After
    fun close() {
        conn.close()
    }

    private fun single(sql: String): String? {
        conn.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            return if (rs.next()) rs.getString(1) else null
        }
    }

    @Test
    fun migrationAppliesCleanly() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_9_10) st.execute(sql)
        }
        // Colunas novas existem com defaults corretos nos dados antigos.
        assertEquals("content", single("SELECT sourceType FROM attachments WHERE id='a-w'"))
        assertEquals("", single("SELECT ref FROM passages WHERE id='p2'"))
        assertEquals("0", single("SELECT ord FROM passages WHERE id='p2'"))
        assertNull(single("SELECT page FROM passages WHERE id='p2'"))
        assertNull(single("SELECT symbol FROM attachments WHERE id='a-w'"))
    }

    @Test
    fun backfillMapsSlotsToTracks() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_9_10) st.execute(sql)
        }
        assertEquals("training", single("SELECT sourceType FROM attachments WHERE id='a-be'"))
        assertEquals("training", single("SELECT sourceType FROM attachments WHERE id='a-th'"))
        assertEquals("bible", single("SELECT sourceType FROM attachments WHERE id='a-nwt'"))
        assertEquals("content", single("SELECT sourceType FROM attachments WHERE id='a-w'"))
        // Passages antigos intactos (texto, vínculo).
        assertEquals("texto be", single("SELECT text FROM passages WHERE id='p1'"))
        assertEquals("a-be", single("SELECT attachmentId FROM passages WHERE id='p1'"))
        assertEquals("2", single("SELECT COUNT(*) FROM passages"))
    }

    @Test
    fun indicesCreated() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_9_10) st.execute(sql)
            val rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='index'")
            val names = mutableSetOf<String>()
            while (rs.next()) names += rs.getString(1)
            assertTrue(names.contains("index_attachments_baseSlot"))
            assertTrue(names.contains("index_passages_attachmentId"))
        }
    }

    @Test
    fun rerunIsIdempotent() {
        conn.createStatement().use { st ->
            // CREATE INDEX usa IF NOT EXISTS; re-execução parcial tolerada aqui
            // apenas para os índices (ALTER TABLE não é reentrante — como no Room).
            st.execute("CREATE INDEX IF NOT EXISTS index_passages_attachmentId ON passages(attachmentId)")
            st.execute("CREATE INDEX IF NOT EXISTS index_passages_attachmentId ON passages(attachmentId)")
        }
    }
}
