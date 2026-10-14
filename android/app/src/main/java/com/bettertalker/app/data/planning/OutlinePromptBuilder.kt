package com.bettertalker.app.data.planning

import com.bettertalker.app.data.util.materialMinutesOf
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
        val source = request.sourceContent?.trim()
        if (!source.isNullOrEmpty()) return buildFromSource(request, source)
        val bible = if (request.bibleRefs.isEmpty()) " (nenhuma fornecida)"
        else request.bibleRefs.joinToString("\n- ", prefix = "\n- ")
        val pubs = if (request.publicationRefs.isEmpty()) " (nenhuma fornecida)"
        else request.publicationRefs.joinToString("\n- ", prefix = "\n- ") {
            buildString {
                append(it.symbol)
                if (it.page != null) append(" p. ${it.page}")
                if (it.paragraph != null) append(" §${it.paragraph}")
            }
        }
        val methods = if (request.methodPrinciples.isEmpty()) " (nenhum fornecido)"
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

    /**
     * F5b (Modo C) — prompt de ESTRUTURAÇÃO: o usuário colou conteúdo de
     * publicação numa seção e o LLM divide ESTE conteúdo em seções com
     * tempos e refs. O tema/título do esboço é derivado do conteúdo (o
     * `request.theme` é só o título atual da seção e não entra no prompt).
     * O conteúdo bruto é truncado em [MAX_SOURCE_CHARS].
     */
    private fun buildFromSource(request: OutlineGenerationRequest, source: String): String {
        val content = if (source.length > MAX_SOURCE_CHARS) {
            source.take(MAX_SOURCE_CHARS) + "\n…(conteúdo truncado)"
        } else {
            source
        }
        val bible = if (request.bibleRefs.isEmpty()) " (nenhuma encontrada)"
        else request.bibleRefs.joinToString("\n- ", prefix = "\n- ")
        val pubs = if (request.publicationRefs.isEmpty()) " (nenhuma encontrada)"
        else request.publicationRefs.joinToString("\n- ", prefix = "\n- ") {
            buildString {
                append(it.symbol)
                if (it.page != null) append(" p. ${it.page}")
                if (it.paragraph != null) append(" §${it.paragraph}")
            }
        }
        val methods = if (request.methodPrinciples.isEmpty()) " (nenhum fornecido)"
        else request.methodPrinciples.joinToString("\n- ", prefix = "\n- ")
        val prompt = """
            Estruture este conteúdo em seções com tempos e textos-âncora.

            Conteúdo de origem (texto que o usuário colou numa seção):
            ---
            $content
            ---

            Duração total alvo: ${request.totalMinutes} minutos
            Público: ${request.audience} (GENERAL, NEW, YOUNG ou MATURE)
            Ângulo: ${request.angle} (DOCTRINAL, PRACTICAL ou NARRATIVE)

            Referências bíblicas presentes no conteúdo (use APENAS estas):$bible

            Publicações presentes no conteúdo (use APENAS estas):$pubs

            Princípios de oratória a aplicar:$methods

            Regras:
            - O título do esboço (title) deve ser o tema detectado no próprio conteúdo.
            - O summary deve resumir em 1-2 frases o que o conteúdo trata.
            - Divida o conteúdo em seções com títulos curtos que resumam cada bloco.
            - Distribua os minutes das seções proporcionalmente ao tamanho dos blocos; a soma NÃO deve exceder ${request.totalMinutes}.
            - Cada seção deve ter mainIdea não-vazia, derivada do conteúdo (não invente).
            - Preserve as referências já citadas no conteúdo.
            - Pelo menos uma seção deve ter bibleRefs não-vazio.
            - Títulos das seções devem ser únicos (case-insensitive).
            - bibleRefs devem ser EXATAMENTE um subconjunto das referências bíblicas disponíveis.
            - publicationRefs devem ser EXATAMENTE um subconjunto das publicações disponíveis.
            - Não invente referências bíblicas nem publicações.

            Retorne APENAS JSON válido no formato abaixo, sem markdown, sem ```json, sem texto extra:
            {"title":"string","summary":"string","sections":[{"title":"string","minutes":0,"mainIdea":"string","bibleRefs":["string"],"publicationRefs":[{"symbol":"string","page":0,"paragraph":0}],"methodPrinciple":"string"}]}
        """.trimIndent()
        // #8b: hint V&M — discurso completo com a duração da parte.
        val vmHint = request.materialNature?.let { nature ->
            val minutes = materialMinutesOf(nature) ?: request.totalMinutes
            "Este material é da reunião Vida e Ministério (discurso).\n" +
                "Gere um discurso COMPLETO: introdução, desenvolvimento e conclusão.\n" +
                "Duração alvo: $minutes minutos."
        }
        return if (vmHint == null) {
            prompt
        } else {
            prompt.replaceFirst(
                "Estruture este conteúdo em seções com tempos e textos-âncora.\n",
                "Estruture este conteúdo em seções com tempos e textos-âncora.\n\n$vmHint\n",
            )
        }
    }

    companion object {
        /** F5b: teto do conteúdo colado injetado no prompt (caracteres). */
        const val MAX_SOURCE_CHARS = 12_000
    }
}
