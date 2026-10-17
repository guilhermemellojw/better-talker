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
 * #26-fix-c: executa o SQL REAL da MIGRATION_18_19 (mesmas strings da
 * produção) contra o schema v18 do chat_messages, em SQLite de verdade.
 * 1. banco v18; 2. mensagens existentes; 3. migration; 4. dados intactos e
 * ativos; 5. soft delete (isActive = 0) esconde das leituras. Sem Android.
 */
class ChatSoftDeleteMigrationTest {

    private lateinit var conn: Connection

    /** Schema v18 do chat_messages (antes do soft delete). */
    private fun v18Chat(): String = """
        CREATE TABLE chat_messages (
            id TEXT NOT NULL, noteId TEXT NOT NULL, fromMe INTEGER NOT NULL,
            kind TEXT NOT NULL, payload TEXT NOT NULL, createdAt INTEGER NOT NULL,
            sectionId TEXT, PRIMARY KEY(id))
    """.trimIndent()

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute(v18Chat())
            st.execute(
                "INSERT INTO chat_messages VALUES " +
                    "('m1','n','1','text','{\"text\":\"pergunta\"}',10,NULL)"
            )
            st.execute(
                "INSERT INTO chat_messages VALUES " +
                    "('m2','n','0','text','{\"text\":\"resposta\"}',20,NULL)"
            )
        }
    }

    @After
    fun close() {
        conn.close()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_18_19) st.execute(sql)
        }
    }

    private fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    private fun singleInt(sql: String): Int {
        conn.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            return if (rs.next()) rs.getInt(1) else -1
        }
    }

    private fun singleString(sql: String): String? {
        conn.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            return if (rs.next()) rs.getString(1) else null
        }
    }

    @Test
    fun migracao_mantemDadosEMensagensAntigasAtivas() {
        migrate()
        // Dados intactos e as mensagens antigas contam como ativas.
        assertEquals("{\"text\":\"pergunta\"}", singleString("SELECT payload FROM chat_messages WHERE id='m1'"))
        assertEquals(2, singleInt("SELECT COUNT(*) FROM chat_messages WHERE isActive = 1"))
        // Mensagem NOVA pós-migração (sem isActive na lista) herda o default 1.
        exec(
            "INSERT INTO chat_messages (id, noteId, fromMe, kind, payload, createdAt, sectionId) " +
                "VALUES ('m3','n','1','text','{}',30,NULL)"
        )
        assertEquals(1, singleInt("SELECT isActive FROM chat_messages WHERE id='m3'"))
    }

    @Test
    fun softDeleteDeUmaMensagem_escondeDaLeituraDeUI() {
        migrate()
        // Mesma forma da query deactivateMessage do ChatDao.
        exec("UPDATE chat_messages SET isActive = 0 WHERE id = 'm2'")
        assertEquals(1, singleInt("SELECT COUNT(*) FROM chat_messages WHERE noteId='n' AND isActive = 1"))
        assertEquals("m1", singleString("SELECT id FROM chat_messages WHERE noteId='n' AND isActive = 1"))
    }

    @Test
    fun softDeleteAPartirDoTimestamp_escondePerguntaEResposta() {
        migrate()
        // Mesma forma da query deactivateMessagesFrom do ChatDao.
        exec(
            "UPDATE chat_messages SET isActive = 0 WHERE noteId = 'n' " +
                "AND sectionId IS NULL AND createdAt >= 10"
        )
        assertEquals(0, singleInt("SELECT COUNT(*) FROM chat_messages WHERE noteId='n' AND isActive = 1"))
        // Linhas seguem no banco (histórico preservado).
        assertEquals(2, singleInt("SELECT COUNT(*) FROM chat_messages WHERE noteId='n'"))
        assertTrue(singleInt("SELECT isActive FROM chat_messages WHERE id='m1'") == 0)
    }
}
