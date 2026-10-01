package com.bettertalker.app

import com.bettertalker.app.data.db.MigrationSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Regressão do wipe P0 encontrado na validação 3.2.4 (device): salvar a nota
 * apagava speech_sections/sub_points.
 *
 * Causa raiz: `NoteDao.upsert` usava `@Insert(REPLACE)` → SQL
 * `INSERT OR REPLACE INTO notes` = DELETE+INSERT da linha pai no SQLite →
 * `ON DELETE CASCADE` derrubava as seções (e os sub-pontos, em cascata).
 * O fix usa `@Upsert` (INSERT; no conflito de unicidade → UPDATE), que nunca
 * deleta a linha pai.
 *
 * Sem Android/Room (padrão do projeto): o DDL REAL das migrations roda em
 * SQLite de verdade (sqlite-jdbc) e o SQL gerado do Room é conferido no
 * bytecode da própria app.
 */
class NoteUpsertCascadeTest {

    private lateinit var conn: Connection

    private fun notesV15(): String = """
        CREATE TABLE notes (
            id TEXT NOT NULL PRIMARY KEY,
            title TEXT NOT NULL,
            discourseType TEXT NOT NULL DEFAULT 'S34_DISCOURSE'
        )
    """.trimIndent()

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            // Room liga FOREIGN KEY em runtime; espelhamos aqui.
            st.execute("PRAGMA foreign_keys = ON")
            st.execute(notesV15())
            // DDL REAL (mesmas strings da produção).
            for (sql in MigrationSql.MIGRATION_12_13) st.execute(sql)
            for (sql in MigrationSql.MIGRATION_13_14) st.execute(sql)
        }
        seed()
    }

    @After
    fun close() {
        conn.close()
    }

    /** Nota com 1 seção BODY e 1 sub-ponto — o estado pós-vínculo do esboço. */
    private fun seed() {
        conn.createStatement().use { st ->
            st.execute("INSERT INTO notes (id, title) VALUES ('n1', 'antes')")
            st.execute(
                "INSERT INTO speech_sections " +
                    "(id, noteId, `order`, role, title, minutes, contentHtml, " +
                    "bibleRefsJson, publicationRefsJson, methodPrinciple, createdAt, updatedAt) " +
                    "VALUES ('s1','n1',0,'BODY','Seção',5,'corpo','[]','[]',NULL,1,1)"
            )
            st.execute(
                "INSERT INTO sub_points " +
                    "(id, sectionId, `order`, outlineText, bibleRefsJson, " +
                    "publicationRefsJson, instruction, developedHtml, createdAt, updatedAt) " +
                    "VALUES ('sp1','s1',0,'Ponto','[]','[]',NULL,'',1,1)"
            )
        }
    }

    private fun single(sql: String): String? {
        conn.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            return if (rs.next()) rs.getString(1) else null
        }
    }

    private fun count(sql: String): Int = single(sql)!!.toInt()

    /**
     * Documenta o comportamento que causou o bug: REPLACE na nota executa
     * DELETE+INSERT do pai e o CASCADE derruba seções e sub-pontos.
     * Se este teste falhar, o schema/PRAGMA mudou — revise o fix.
     */
    @Test
    fun sqlAntigoComReplaceApagariaFilhos() {
        conn.createStatement().use { st ->
            st.execute(
                "INSERT OR REPLACE INTO notes (id, title, discourseType) " +
                    "VALUES ('n1', 'depois', 'S34_DISCOURSE')"
            )
        }
        assertEquals("REPLACE deveria apagar as seções (bug)", 0, count("SELECT COUNT(*) FROM speech_sections"))
        assertEquals("REPLACE deveria apagar os sub-pontos (bug)", 0, count("SELECT COUNT(*) FROM sub_points"))
    }

    /**
     * Espelha a semântica do `@Upsert` do Room 2.8 (EntityUpsertAdapter):
     * tenta INSERT; no conflito de unicidade, UPDATE — nunca DELETE.
     */
    @Test
    fun upsertSemReplacePreservaFilhos() {
        val entrouNoCatch = try {
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO notes (id, title, discourseType) " +
                        "VALUES ('n1', 'depois', 'S34_DISCOURSE')"
                )
            }
            false // id 'n1' já existe: o INSERT tem de falhar
        } catch (_: SQLException) {
            conn.createStatement().use { st ->
                st.execute(
                    "UPDATE notes SET title = 'depois', discourseType = 'S34_DISCOURSE' " +
                        "WHERE id = 'n1'"
                )
            }
            true
        }
        assertTrue("INSERT duplicado deveria conflitar", entrouNoCatch)
        assertEquals("depois", single("SELECT title FROM notes WHERE id='n1'"))
        assertEquals(1, count("SELECT COUNT(*) FROM speech_sections"))
        assertEquals(1, count("SELECT COUNT(*) FROM sub_points"))
    }

    /**
     * Tripwire no código gerado pelo Room: o SQL de `notes` não pode conter
     * REPLACE. Se alguém trocar `@Upsert` por `@Insert(REPLACE)` de novo, o
     * bytecode gerado denuncia e este teste falha.
     */
    @Test
    fun sqlGeradoDoRoomNaoUsaReplaceEmNotes() {
        val nomes = buildList {
            add("com/bettertalker/app/data/db/NoteDao_Impl.class")
            for (i in 1..12) add("com/bettertalker/app/data/db/NoteDao_Impl\$$i.class")
        }
        var lidos = 0
        var achouInsertPuro = false
        for (nome in nomes) {
            val res = javaClass.classLoader!!.getResource(nome) ?: continue
            lidos++
            val texto = res.openStream().use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            assertFalse(
                "SQL gerado não pode usar REPLACE em notes ($nome)",
                texto.contains("INSERT OR REPLACE INTO `notes`")
            )
            if (texto.contains("INSERT INTO `notes`")) achouInsertPuro = true
        }
        assertTrue("NoteDao_Impl não encontrado no classpath do teste", lidos > 0)
        assertTrue("INSERT INTO `notes` não encontrado no código gerado", achouInsertPuro)
    }
}
