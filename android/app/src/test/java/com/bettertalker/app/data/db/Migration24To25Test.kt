package com.bettertalker.app.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Executa o SQL REAL da MIGRATION_24_25 (uid + remoção da FK). */
class Migration24To25Test {

    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("CREATE TABLE note_images (id TEXT NOT NULL PRIMARY KEY)")
            st.execute(
                "CREATE TABLE image_backups (" +
                    "imageId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, " +
                    "cloudPath TEXT, sizeBytes INTEGER NOT NULL, sha256 TEXT, " +
                    "uploadedAt INTEGER, error TEXT, attempts INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "FOREIGN KEY(imageId) REFERENCES note_images(id) ON DELETE CASCADE)"
            )
            st.execute("INSERT INTO note_images (id) VALUES ('img-1')")
            st.execute(
                "INSERT INTO image_backups VALUES " +
                    "('img-1','uploaded','users/u/images/n/img-1.webp',42,'abc',9,NULL,1,10)"
            )
        }
    }

    @After
    fun close() {
        conn.close()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            for (sql in MigrationSql.MIGRATION_24_25) st.execute(sql)
        }
    }

    private fun single(sql: String): String? =
        conn.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            if (rs.next()) rs.getString(1) else null
        }

    @Test
    fun preservaDadosEAdicionaUidNulo() {
        migrate()
        assertEquals("1", single("SELECT COUNT(*) FROM image_backups"))
        assertEquals("uploaded", single("SELECT state FROM image_backups"))
        assertNull(single("SELECT uid FROM image_backups WHERE imageId='img-1'"))
    }

    @Test
    fun linhaDeletedSobreviveAExclusaoDaImagem() {
        migrate()
        conn.createStatement().use { st ->
            st.execute("DELETE FROM note_images WHERE id='img-1'")
        }
        assertEquals("1", single("SELECT COUNT(*) FROM image_backups"))
    }

    @Test
    fun indicesSaoRecriados() {
        migrate()
        assertEquals(
            "2",
            single(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' " +
                    "AND name IN ('index_image_backups_state','index_image_backups_updatedAt')"
            ),
        )
    }

    @Test
    fun schemaFinalTemTabela() {
        migrate()
        assertEquals(
            "image_backups",
            single("SELECT name FROM sqlite_master WHERE type='table' AND name='image_backups'"),
        )
        assertNull(
            single("SELECT name FROM sqlite_master WHERE name='image_backups_new'"),
        )
    }
}
