package com.bettertalker.app.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * #20-fix-a: executa o SQL REAL da MIGRATION_17_18 (mesmas strings da
 * produção) contra um schema v17 em SQLite de verdade. Aditiva: nenhum dado
 * perdido e registros antigos ficam com `tribunaJson` NULL.
 */
class Migration18Test {

    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            // Baseline v17 = v16 + approvedAt (MIGRATION_16_17).
            st.execute(
                "CREATE TABLE speech_sections (" +
                    "id TEXT NOT NULL PRIMARY KEY, noteId TEXT NOT NULL, `order` INTEGER NOT NULL, " +
                    "role TEXT NOT NULL, title TEXT NOT NULL, minutes INTEGER NOT NULL, " +
                    "contentHtml TEXT NOT NULL, bibleRefsJson TEXT NOT NULL, " +
                    "publicationRefsJson TEXT NOT NULL, methodPrinciple TEXT, " +
                    "objective TEXT, agreedApproach TEXT, approvedAt INTEGER, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            )
            st.execute(
                "INSERT INTO speech_sections VALUES " +
                    "('s1','n1',0,'BODY','Tópico',5,'<p>texto antigo</p>','[]','[]',NULL,'obj','abord',NULL,1,2)"
            )
        }
    }

    @After
    fun close() {
        conn.close()
    }

    private fun migrate() {
        for (sql in MigrationSql.MIGRATION_17_18) {
            conn.createStatement().use { it.execute(sql) }
        }
    }

    @Test
    fun migracaoAdicionaTribunaJsonNullable() {
        migrate()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT tribunaJson FROM speech_sections WHERE id='s1'")
            assertEquals(true, rs.next())
            assertNull(rs.getString(1))
        }
    }

    @Test
    fun registroAntigoContinuaIntacto() {
        migrate()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT contentHtml FROM speech_sections WHERE id='s1'")
            assertEquals(true, rs.next())
            assertEquals("<p>texto antigo</p>", rs.getString(1))
        }
    }
}
