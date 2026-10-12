package com.bettertalker.app.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * F3a: executa o SQL REAL da MIGRATION_16_17 (mesmas strings da produção)
 * contra um schema v16 em SQLite de verdade. Aditiva: nenhum dado perdido e
 * registros antigos ficam com `approvedAt` NULL (não aprovada).
 */
class Migration17Test {

    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            // Baseline v16 = v15 + objective + agreedApproach (MIGRATION_15_16).
            st.execute(
                "CREATE TABLE speech_sections (" +
                    "id TEXT NOT NULL PRIMARY KEY, noteId TEXT NOT NULL, `order` INTEGER NOT NULL, " +
                    "role TEXT NOT NULL, title TEXT NOT NULL, minutes INTEGER NOT NULL, " +
                    "contentHtml TEXT NOT NULL, bibleRefsJson TEXT NOT NULL, " +
                    "publicationRefsJson TEXT NOT NULL, methodPrinciple TEXT, " +
                    "objective TEXT, agreedApproach TEXT, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            )
            st.execute(
                "INSERT INTO speech_sections VALUES " +
                    "('s1','n1',0,'BODY','Tópico',5,'<p>texto antigo</p>','[]','[]',NULL,'obj','abord',1,2)"
            )
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
    fun migrationAddsApprovedAtWithoutDataLoss() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_16_17) st.execute(sql)
        }
        // Dados antigos intactos.
        assertEquals("<p>texto antigo</p>", single("SELECT contentHtml FROM speech_sections WHERE id='s1'"))
        assertEquals("obj", single("SELECT objective FROM speech_sections WHERE id='s1'"))
        assertEquals("abord", single("SELECT agreedApproach FROM speech_sections WHERE id='s1'"))
        assertEquals("2", single("SELECT updatedAt FROM speech_sections WHERE id='s1'"))
        // Coluna nova existe, anulável, NULL nos registros antigos.
        assertEquals(
            "INTEGER",
            single("SELECT type FROM pragma_table_info('speech_sections') WHERE name='approvedAt'"),
        )
        assertEquals(
            "0",
            single("SELECT `notnull` FROM pragma_table_info('speech_sections') WHERE name='approvedAt'"),
        )
        assertNull(single("SELECT approvedAt FROM speech_sections WHERE id='s1'"))
    }

    @Test
    fun migrationAcceptsApprovedRows() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_16_17) st.execute(sql)
            st.execute("UPDATE speech_sections SET approvedAt = 777 WHERE id = 's1'")
        }
        assertEquals("777", single("SELECT approvedAt FROM speech_sections WHERE id='s1'"))
    }
}
