package com.bettertalker.app.data.planning

import com.bettertalker.app.domain.planning.OutlineGenerationRequest

/**
 * Monta o prompt textual enviado ao LLM para gerar um esboço.
 */
interface OutlinePromptBuilder {
    /**
     * Constrói o prompt em PT-BR a partir de [request].
     */
    fun build(request: OutlineGenerationRequest): String
}

/**
 * Implementação default: prompt com tema, duração, público, ângulo,
 * listas de refs/publicações/princípios, formato JSON esperado e
 * regras anti-alucinação (usar só o fornecido, não inventar).
 */
class DefaultOutlinePromptBuilder : OutlinePromptBuilder {

    override fun build(request: OutlineGenerationRequest): String {
        val bible = if (request.bibleRefs.isEmpty()) "(nenhuma fornecida)"
        else request.bibleRefs.joinToString("\n- ", prefix = "\n- ")
        val pubs = if (request.publicationRefs.isEmpty()) "(nenhuma fornecida)"
        else request.publicationRefs.joinToString("\n- ", prefix = "\n- ") {
            buildString {
                append(it.symbol)
                if (it.page != null) append(" p. ${it.page}")
                if (it.paragraph != null) append(" §${it.paragraph}")
            }
        }
        val methods = if (request.methodPrinciples.isEmpty()) "(nenhum fornecido)"
        else request.methodPrinciples.joinToString("\n- ", prefix = "\n- ")
        return """
            Gere um esboço de discurso em português (PT-BR).

            Tema: ${request.theme}
            Duração total: ${request.totalMinutes} minutos
            Público: ${request.audience} (GENERAL, NEW, YOUNG ou MATURE)
            Ângulo: ${request.angle} (DOCTRINAL, PRACTICAL ou NARRATIVE)

            Referências bíblicas disponíveis (use APENAS estas):$bible

            Publicações disponíveis (use APENAS estas):$pubs

            Princípios de oratória a aplicar:$methods

            Regras:
            - A soma dos minutes das seções NÃO deve exceder ${request.totalMinutes}.
            - Cada seção deve ter mainIdea não-vazia.
            - Pelo menos uma seção deve ter bibleRefs não-vazio.
            - Títulos das seções devem ser únicos (case-insensitive).
            - bibleRefs devem ser EXATAMENTE um subconjunto das referências bíblicas disponíveis.
            - publicationRefs devem ser EXATAMENTE um subconjunto das publicações disponíveis.
            - Não invente referências bíblicas nem publicações.

            Retorne APENAS JSON válido no formato abaixo, sem markdown, sem ```json, sem texto extra:
            {"title":"string","summary":"string","sections":[{"title":"string","minutes":0,"mainIdea":"string","bibleRefs":["string"],"publicationRefs":[{"symbol":"string","page":0,"paragraph":0}],"methodPrinciple":"string"}]}
        """.trimIndent()
    }
}
