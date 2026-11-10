package com.bettertalker.app.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Executa o SQL REAL da MIGRATION_23_24 contra SQLite. */
class Migration23To24Test {

    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("CREATE TABLE note_images (id TEXT NOT NULL PRIMARY KEY)")
            st.execute("INSERT INTO note_images (id) VALUES ('img-1')")
        }
    }

    @After
    fun close() {
        conn.close()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_23_24) st.execute(sql)
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
            "image_backups",
            single("SELECT name FROM sqlite_master WHERE type='table' AND name='image_backups'"),
        )
        assertEquals(
            "2",
            single(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' " +
                    "AND name IN ('index_image_backups_state','index_image_backups_updatedAt')"
            ),
        )
    }

    @Test
    fun guardaEstadoDoUpload() {
        migrate()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO image_backups VALUES " +
                    "('img-1','uploaded','users/u/images/n/img-1.webp',1234,'abc',99,NULL,1,100)"
            )
        }
        assertEquals(
            "uploaded",
            single("SELECT state FROM image_backups WHERE imageId='img-1'"),
        )
        assertEquals(
            "users/u/images/n/img-1.webp",
            single("SELECT cloudPath FROM image_backups WHERE imageId='img-1'"),
        )
    }

    @Test
    fun excluirImagemApagaBackupEmCascata() {
        migrate()
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO image_backups VALUES " +
                    "('img-1','pending',NULL,0,NULL,NULL,NULL,0,100)"
            )
            st.execute("DELETE FROM note_images WHERE id='img-1'")
        }
        assertEquals("0", single("SELECT COUNT(*) FROM image_backups"))
    }

    @Test
    fun reexecucaoEhIdempotente() {
        migrate()
        migrate()
        assertEquals(
            "1",
            single(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='image_backups'"
            ),
        )
        assertTrue(single("SELECT COUNT(*) FROM image_backups") == "0")
    }
}
