package com.bettertalker.app.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Fase A — executa o SQL REAL da MIGRATION_19_20 contra um SQLite de verdade.
 * A parte 6 "Discurso" (título "Discurso...") vira MINISTRY_TALK; demos e
 * discursos de outras seções ficam intactos.
 */
class Migration19To20Test {

    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE notes (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    discourseType TEXT NOT NULL DEFAULT 'S34_DISCOURSE'
                )
                """.trimIndent()
            )
            st.execute(
                "INSERT INTO notes (id, title, discourseType) VALUES " +
                    "('n1', 'Discurso: Seja corajoso (5 min)', 'MINISTRY_PART')," +
                    "('n2', 'Cultivando o interesse (4 min)', 'MINISTRY_PART')," +
                    "('n3', 'Ter esperança (10 min)', 'TREASURES_TALK')," +
                    "('n4', '  discurso: minúsculo (5 min)', 'MINISTRY_PART')"
            )
        }
    }

    @After
    fun close() {
        conn.close()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_19_20) st.execute(sql)
        }
    }

    private fun typeOf(id: String): String? =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT discourseType FROM notes WHERE id = '$id'")
            if (rs.next()) rs.getString(1) else null
        }

    @Test
    fun parte6Discurso_viraMinistryTalk() {
        migrate()
        assertEquals("MINISTRY_TALK", typeOf("n1"))
        assertEquals("MINISTRY_TALK", typeOf("n4"))
    }

    @Test
    fun demoEDiscursoDeOutraSecao_ficamIntactos() {
        migrate()
        assertEquals("MINISTRY_PART", typeOf("n2"))
        assertEquals("TREASURES_TALK", typeOf("n3"))
    }
}
