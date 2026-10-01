package com.bettertalker.app.data.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariante do modo estrito do Groq (`response_format: json_schema`):
 * TODO objeto do schema precisa de `"additionalProperties":false` — e cada
 * `required` precisa listar todas as propriedades do objeto. Sem isso o Groq
 * rejeita o request inteiro com HTTP 400 (draft/esboço falhavam silenciosos).
 *
 * Puro: só inspeciona as strings dos schemas (sem rede, sem provider).
 */
class StrictSchemaTest {

    private fun occurrences(s: String, needle: String): Int = s.split(needle).size - 1

    private fun assertAllObjectsClosed(name: String, schema: String) {
        val objects = occurrences(schema, "\"type\":\"object\"")
        val closed = occurrences(schema, "\"additionalProperties\":false")
        assertTrue("$name deve ter ao menos um objeto", objects > 0)
        assertEquals(
            "$name: todo objeto precisa de additionalProperties:false",
            objects,
            closed,
        )
    }

    @Test
    fun sectionDraftSchema_temAdditionalPropertiesFalseEmTodosOsObjetos() {
        assertAllObjectsClosed("SECTION_DRAFT_SCHEMA", SectionGeneratorImpl.SECTION_DRAFT_SCHEMA)
    }

    @Test
    fun outlineSchema_temAdditionalPropertiesFalseEmTodosOsObjetos() {
        assertAllObjectsClosed("OUTLINE_SCHEMA", OutlineGeneratorImpl.OUTLINE_SCHEMA)
    }

    /**
     * O modo estrito também exige `required` com todas as propriedades.
     * Verificação por conteúdo — barata e suficiente para travar regressão.
     */
    @Test
    fun outlineSchema_requiredCobreTodasAsPropriedades() {
        val s = OutlineGeneratorImpl.OUTLINE_SCHEMA
        assertTrue(s.contains("\"required\":[\"title\",\"summary\",\"sections\"]"))
        assertTrue(
            s.contains(
                "\"required\":[\"title\",\"minutes\",\"mainIdea\",\"bibleRefs\"," +
                    "\"publicationRefs\",\"methodPrinciple\"]",
            ),
        )
        assertTrue(s.contains("\"required\":[\"symbol\",\"page\",\"paragraph\"]"))
    }
}
