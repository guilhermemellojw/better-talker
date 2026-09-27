package com.bettertalker.app

import com.bettertalker.app.data.db.MigrationSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Fase 19-B.3 (§17/§22.31-32): executa o SQL REAL da MIGRATION_10_11 (mesmas
 * strings da produção) contra schema v10 em SQLite de verdade.
 * 1. banco v10; 2. dados existentes; 3. migration; 4. dados intactos;
 * 5. tabelas s34 funcionam. Sem Android/Room.
 */
class S34MigrationTest {

    private lateinit var conn: Connection

    private fun v10Attachments(): String = """
        CREATE TABLE attachments (
            id TEXT NOT NULL, noteId TEXT, fileName TEXT NOT NULL, kind TEXT NOT NULL,
            sizeBytes INTEGER NOT NULL, appPath TEXT NOT NULL, indexed INTEGER NOT NULL,
            addedAt INTEGER NOT NULL, baseSlot TEXT, status TEXT NOT NULL, error TEXT,
            downloadId INTEGER NOT NULL, sourceUrl TEXT, sourceType TEXT NOT NULL DEFAULT 'content',
            symbol TEXT, PRIMARY KEY(id))
    """.trimIndent()

    private fun v10Passages(): String = """
        CREATE TABLE passages (
            id TEXT NOT NULL, attachmentId TEXT NOT NULL, text TEXT NOT NULL,
            normalized TEXT NOT NULL, section TEXT NOT NULL, ref TEXT NOT NULL DEFAULT '',
            page INTEGER, paragraph INTEGER, ord INTEGER NOT NULL DEFAULT 0,
            trainingCategory TEXT, PRIMARY KEY(id))
    """.trimIndent()

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute(v10Attachments())
            st.execute(v10Passages())
            st.execute("INSERT INTO attachments VALUES ('a-w','n','w.pdf','pdf',1,'/x',1,1,NULL,'ready',NULL,-1,NULL,'content',NULL)")
            st.execute("INSERT INTO passages VALUES ('p1','a-w','texto w','texto w','', '', NULL, NULL, 0, NULL)")
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
    fun migrateV10toV11KeepsOldData() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_10_11) st.execute(sql)
        }
        // dados antigos intactos
        assertEquals("w.pdf", single("SELECT fileName FROM attachments WHERE id='a-w'"))
        assertEquals("texto w", single("SELECT text FROM passages WHERE id='p1'"))
        // tabelas novas existem e funcionam
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO s34_outlines VALUES " +
                    "('s34-abc','a-w','S-34','Tema',NULL,'[]',1,2,1)"
            )
            st.execute(
                "INSERT INTO s34_sections VALUES " +
                    "('sec-1','s34-abc',1,'Ponto','corpo',NULL,10)"
            )
            st.execute(
                "INSERT INTO s34_subsections VALUES ('sec-1-1','sec-1',1,'a) x',11)"
            )
            st.execute(
                "INSERT INTO s34_references VALUES " +
                    "('s34-abc-ref-1','s34-abc','sec-1',NULL,1,'BIBLE'," +
                    "'João 17:17','João|17|17',12,'João','joao',17,17,NULL,NULL,NULL,NULL)"
            )
        }
        assertEquals("Tema", single("SELECT title FROM s34_outlines WHERE id='s34-abc'"))
        assertEquals("Ponto", single("SELECT title FROM s34_sections WHERE id='sec-1'"))
        assertEquals("a) x", single("SELECT content FROM s34_subsections WHERE id='sec-1-1'"))
        assertEquals(
            "João|17|17",
            single("SELECT normalizedReference FROM s34_references WHERE id='s34-abc-ref-1'")
        )
        // ordem determinística por position
        assertEquals(
            "sec-1",
            single("SELECT id FROM s34_sections WHERE outlineId='s34-abc' ORDER BY position ASC LIMIT 1")
        )
    }

    @Test
    fun migrateV11toV12ReancoraChavesESemPerda() {
        // v11: s34_* com chaves locais (colidiam entre outlines).
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_10_11) st.execute(sql)
            st.execute(
                "INSERT INTO s34_outlines VALUES " +
                    "('s34-abc','a-w','S-34','Tema',NULL,'[]',1,2,1)"
            )
            st.execute("INSERT INTO s34_sections VALUES ('sec-1','s34-abc',1,'Ponto','corpo',NULL,10)")
        }
        assertEquals("sec-1", single("SELECT id FROM s34_sections WHERE outlineId='s34-abc'"))
        // migration v11 -> v12 (recria o cache derivado)
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_11_12) st.execute(sql)
        }
        // Dados do usuário intactos.
        assertEquals("w.pdf", single("SELECT fileName FROM attachments WHERE id='a-w'"))
        assertEquals("texto w", single("SELECT text FROM passages WHERE id='p1'"))
        // Cache s34 vazio (reconstruível) e operante com chaves novas.
        assertEquals(null, single("SELECT id FROM s34_sections WHERE outlineId='s34-abc'"))
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO s34_sections VALUES " +
                    "('s34-new:sec-1','s34-new',1,'Ponto','corpo',NULL,10)"
            )
        }
        assertEquals(
            "s34-new:sec-1",
            single("SELECT id FROM s34_sections WHERE outlineId='s34-new'")
        )
    }

    @Test
    fun migrationIsIdempotent() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_10_11) st.execute(sql)
            for (sql in MigrationSql.MIGRATION_10_11) st.execute(sql)
        }
        assertTrue(true)
    }
}
