package com.bettertalker.app.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Executa o SQL REAL da MIGRATION_25_26 (publication_units). */
class Migration25To26Test {

    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("CREATE TABLE attachments (id TEXT NOT NULL PRIMARY KEY)")
            st.execute("INSERT INTO attachments (id) VALUES ('att-1')")
        }
    }

    @After
    fun close() {
        conn.close()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_25_26) st.execute(sql)
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
            "publication_units",
            single(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='publication_units'"
            ),
        )
        assertEquals(
            "4",
            single(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' " +
                    "AND name IN (" +
                    "'index_publication_units_attachmentId'," +
                    "'index_publication_units_parentId'," +
                    "'index_publication_units_attachmentId_ord'," +
                    "'index_publication_units_attachmentId_canonicalKey')"
            ),
        )
    }

    @Test
    fun colunasFicamNaOrdemDoEntity() {
        migrate()
        val columns = mutableListOf<String>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("PRAGMA table_info(publication_units)")
            while (rs.next()) columns += rs.getString("name")
        }
        assertEquals(
            listOf(
                "id", "attachmentId", "parentId", "kind", "number", "title",
                "canonicalKey", "ord", "startOrd", "endOrd", "startPage", "endPage",
            ),
            columns,
        )
    }

    @Test
    fun excluirAnexoApagaUnidadesEmCascata() {
        migrate()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO publication_units VALUES " +
                    "('att-1-u0','att-1',NULL,'CHAPTER','1','Capítulo 1','jr:chapter:capitulo-1'," +
                    "0,0,10,1,4)"
            )
            st.execute("DELETE FROM attachments WHERE id='att-1'")
        }
        assertEquals("0", single("SELECT COUNT(*) FROM publication_units"))
    }

    @Test
    fun canonicalKeyEhUnicaPorAnexo() {
        migrate()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO publication_units VALUES " +
                    "('att-1-u0','att-1',NULL,'CHAPTER','1','Capítulo 1','jr:chapter:capitulo-1'," +
                    "0,0,10,1,4)"
            )
            var failed = false
            try {
                st.execute(
                    "INSERT INTO publication_units VALUES " +
                        "('att-1-u1','att-1',NULL,'CHAPTER','1','Capítulo 1','jr:chapter:capitulo-1'," +
                        "1,11,20,5,8)"
                )
            } catch (_: java.sql.SQLException) {
                failed = true
            }
            assertTrue("canonicalKey duplicada deve falhar", failed)
        }
    }

    @Test
    fun reexecucaoEhIdempotente() {
        migrate()
        migrate()
        assertEquals(
            "1",
            single(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='publication_units'"
            ),
        )
        assertEquals("0", single("SELECT COUNT(*) FROM publication_units"))
    }

    @Test
    fun tabelaAuxiliarNaoSobrou() {
        migrate()
        assertNull(
            single(
                "SELECT name FROM sqlite_master WHERE name='publication_units_new'"
            ),
        )
    }
}
