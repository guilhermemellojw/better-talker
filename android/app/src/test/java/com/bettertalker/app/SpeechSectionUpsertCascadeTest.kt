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
 * Regressão da Fase 0 (Copilot mentor): salvar metadados de uma seção
 * (`objective`/`agreedApproach`) não pode apagar os sub-pontos dela.
 *
 * Causa raiz: `SpeechSectionDao.upsert` usava `@Insert(REPLACE)` → SQL
 * `INSERT OR REPLACE INTO speech_sections` = DELETE+INSERT da linha pai no
 * SQLite → `ON DELETE CASCADE` derrubava `sub_points` (a linha de raciocínio).
 * O fix usa `@Upsert` e updates segmentados (`UPDATE ... WHERE id = :id`).
 *
 * Sem Android/Room (padrão do projeto): o DDL REAL das migrations roda em
 * SQLite de verdade (sqlite-jdbc) e o SQL gerado do Room é conferido no
 * bytecode da própria app.
 */
class SpeechSectionUpsertCascadeTest {

    private lateinit var conn: Connection

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            // Room liga FOREIGN KEY em runtime; espelhamos aqui.
            st.execute("PRAGMA foreign_keys = ON")
            st.execute(
                """
                CREATE TABLE notes (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    discourseType TEXT NOT NULL DEFAULT 'S34_DISCOURSE'
                )
                """.trimIndent()
            )
            // DDL REAL (mesmas strings da produção).
            for (sql in MigrationSql.MIGRATION_12_13) st.execute(sql)
            for (sql in MigrationSql.MIGRATION_13_14) st.execute(sql)
            // MIGRATION_15_16 também altera chat_messages; cria o mínimo para
            // aplicar o DDL real de objective/agreedApproach.
            st.execute("CREATE TABLE chat_messages (id TEXT NOT NULL PRIMARY KEY)")
            for (sql in MigrationSql.MIGRATION_15_16) st.execute(sql)
            for (sql in MigrationSql.MIGRATION_16_17) st.execute(sql)
            for (sql in MigrationSql.MIGRATION_17_18) st.execute(sql)
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
     * Documenta o comportamento que causou o bug: REPLACE na seção executa
     * DELETE+INSERT do pai e o CASCADE derruba os sub-pontos.
     * Se este teste falhar, o schema/PRAGMA mudou — revise o fix.
     */
    @Test
    fun sqlAntigoComReplaceApagariaSubPontos() {
        conn.createStatement().use { st ->
            st.execute(
                "INSERT OR REPLACE INTO speech_sections " +
                    "(id, noteId, `order`, role, title, minutes, contentHtml, " +
                    "bibleRefsJson, publicationRefsJson, methodPrinciple, createdAt, updatedAt) " +
                    "VALUES ('s1','n1',0,'BODY','depois',5,'corpo','[]','[]',NULL,2,2)"
            )
        }
        assertEquals(
            "REPLACE deveria apagar os sub-pontos (bug)",
            0,
            count("SELECT COUNT(*) FROM sub_points"),
        )
    }

    /** Espelha `SpeechSectionDao.updateObjective` (UPDATE segmentado). */
    @Test
    fun updateObjetivoPreservaSubPontos() {
        conn.createStatement().use { st ->
            st.execute(
                "UPDATE speech_sections SET objective = 'Objetivo novo', updatedAt = 2 " +
                    "WHERE id = 's1'"
            )
        }
        assertEquals("Objetivo novo", single("SELECT objective FROM speech_sections WHERE id='s1'"))
        assertEquals(1, count("SELECT COUNT(*) FROM sub_points WHERE sectionId='s1'"))
    }

    /** Espelha `SpeechSectionDao.updateAgreedApproach` (UPDATE segmentado). */
    @Test
    fun updateAbordagemPreservaSubPontos() {
        conn.createStatement().use { st ->
            st.execute(
                "UPDATE speech_sections SET agreedApproach = 'Abordagem nova', updatedAt = 3 " +
                    "WHERE id = 's1'"
            )
        }
        assertEquals(
            "Abordagem nova",
            single("SELECT agreedApproach FROM speech_sections WHERE id='s1'"),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM sub_points WHERE sectionId='s1'"))
    }

    /**
     * Espelha a semântica do `@Upsert` do Room 2.8 (EntityUpsertAdapter):
     * tenta INSERT; no conflito de PK, UPDATE — nunca DELETE.
     */
    @Test
    fun upsertSemReplacePreservaSubPontos() {
        val entrouNoCatch = try {
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO speech_sections " +
                        "(id, noteId, `order`, role, title, minutes, contentHtml, " +
                        "bibleRefsJson, publicationRefsJson, methodPrinciple, createdAt, updatedAt) " +
                        "VALUES ('s1','n1',0,'BODY','depois',5,'corpo','[]','[]',NULL,2,2)"
                )
            }
            false // id 's1' já existe: o INSERT tem de falhar
        } catch (_: SQLException) {
            conn.createStatement().use { st ->
                st.execute(
                    "UPDATE speech_sections SET title = 'depois', updatedAt = 2 " +
                        "WHERE id = 's1'"
                )
            }
            true
        }
        assertTrue("INSERT duplicado deveria conflitar", entrouNoCatch)
        assertEquals("depois", single("SELECT title FROM speech_sections WHERE id='s1'"))
        assertEquals(1, count("SELECT COUNT(*) FROM sub_points"))
    }

    /**
     * Tripwire no código gerado pelo Room: o SQL de `speech_sections` não pode
     * conter REPLACE. Se alguém trocar `@Upsert` por `@Insert(REPLACE)` de
     * novo, o bytecode gerado denuncia e este teste falha.
     */
    @Test
    fun sqlGeradoDoRoomNaoUsaReplaceEmSpeechSections() {
        val nomes = buildList {
            add("com/bettertalker/app/data/db/SpeechSectionDao_Impl.class")
            for (i in 1..12) add("com/bettertalker/app/data/db/SpeechSectionDao_Impl\$$i.class")
        }
        var lidos = 0
        var achouInsertPuro = false
        for (nome in nomes) {
            val res = javaClass.classLoader!!.getResource(nome) ?: continue
            lidos++
            val texto = res.openStream().use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            assertFalse(
                "SQL gerado não pode usar REPLACE em speech_sections ($nome)",
                texto.contains("INSERT OR REPLACE INTO `speech_sections`")
            )
            if (texto.contains("INSERT INTO `speech_sections`")) achouInsertPuro = true
        }
        assertTrue("SpeechSectionDao_Impl não encontrado no classpath do teste", lidos > 0)
        assertTrue("INSERT INTO `speech_sections` não encontrado no código gerado", achouInsertPuro)
    }
}
