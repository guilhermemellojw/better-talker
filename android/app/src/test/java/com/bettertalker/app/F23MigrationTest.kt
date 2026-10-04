package com.bettertalker.app

import com.bettertalker.app.data.db.MigrationSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * F2.3: executa o SQL REAL da MIGRATION_15_16 (mesmas strings da produção)
 * contra um schema v15 em SQLite de verdade. Aditiva: nenhum dado perdido e
 * mensagens antigas ficam com `sectionId` NULL (conversa global).
 */
class F23MigrationTest {

    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute(
                "CREATE TABLE speech_sections (" +
                    "id TEXT NOT NULL PRIMARY KEY, noteId TEXT NOT NULL, `order` INTEGER NOT NULL, " +
                    "role TEXT NOT NULL, title TEXT NOT NULL, minutes INTEGER NOT NULL, " +
                    "contentHtml TEXT NOT NULL, bibleRefsJson TEXT NOT NULL, " +
                    "publicationRefsJson TEXT NOT NULL, methodPrinciple TEXT, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            )
            st.execute(
                "CREATE TABLE chat_messages (" +
                    "id TEXT NOT NULL PRIMARY KEY, noteId TEXT NOT NULL, fromMe INTEGER NOT NULL, " +
                    "kind TEXT NOT NULL, payload TEXT NOT NULL, createdAt INTEGER NOT NULL)"
            )
            st.execute(
                "INSERT INTO speech_sections VALUES " +
                    "('s1','n1',1,'BODY','Tópico',5,'<p>texto antigo</p>','[]','[]',NULL,1,1)"
            )
            st.execute(
                "INSERT INTO chat_messages VALUES ('m1','n1',1,'text','{\"text\":\"oi\"}',10)"
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
    fun migrationAddsColumnsWithoutDataLoss() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_15_16) st.execute(sql)
        }
        // Dados antigos intactos.
        assertEquals("<p>texto antigo</p>", single("SELECT contentHtml FROM speech_sections WHERE id='s1'"))
        assertEquals("{\"text\":\"oi\"}", single("SELECT payload FROM chat_messages WHERE id='m1'"))
        // Colunas novas existem e são NULL nos registros antigos.
        assertNull(single("SELECT objective FROM speech_sections WHERE id='s1'"))
        assertNull(single("SELECT agreedApproach FROM speech_sections WHERE id='s1'"))
        assertNull(single("SELECT sectionId FROM chat_messages WHERE id='m1'"))
    }

    @Test
    fun migrationAcceptsNewValues() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_15_16) st.execute(sql)
            st.execute(
                "UPDATE speech_sections SET objective='Levar à ação', " +
                    "agreedApproach='situação → princípio' WHERE id='s1'"
            )
            st.execute("UPDATE chat_messages SET sectionId='s1' WHERE id='m1'")
        }
        assertEquals("Levar à ação", single("SELECT objective FROM speech_sections WHERE id='s1'"))
        assertEquals("situação → princípio", single("SELECT agreedApproach FROM speech_sections WHERE id='s1'"))
        assertEquals("s1", single("SELECT sectionId FROM chat_messages WHERE id='m1'"))
    }
}
