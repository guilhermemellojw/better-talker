package com.bettertalker.app.data.planning

import com.bettertalker.app.data.copilot.GenerationPrompts
import com.bettertalker.app.data.util.htmlToPlainText
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.planning.SpeechPacing
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole

/** Teto da prosa da seção no dossiê (B1). */
private const val MAX_SECTION_PROSE_CHARS = 4000

/** Teto do desenvolvimento de cada sub-ponto no dossiê (B2). */
private const val MAX_SUBPOINT_PROSE_CHARS = 2000

/**
 * Monta o prompt do LLM a partir de um [Dossier].
 *
 * Puro: só consome o Dossier e devolve String. Sem I/O, sem Room.
 * Regras anti-alucinação explícitas (espelho de LlmPrompt.buildRagPrompt
 * e OratoryGeneration.BASE_RULES).
 *
 * O prompt varia por role (BODY/INTRO/CONCLUSION) — 3 instruções de
 * tarefa, com regras base compartilhadas.
 */
interface DossierPromptBuilder {
    fun build(dossier: Dossier): String

    /**
     * Monta SÓ o bloco de contexto (seção + refs + overview) do dossiê,
     * para injetar no prompt do chat. Sem "## TAREFA" (no chat, a tarefa
     * é a mensagem do usuário) e sem "## FORMATO DE SAÍDA".
     *
     * Enxuto por role (F2b): BODY vê só a seção alvo; INTRO/CONCLUSION
     * veem o overview resumido de todas.
     *
     * F5c: `includeSpeakerNote = false` omite "## ORIENTAÇÃO DO ORADOR"
     * quando o bloco estrutural do S-34 já carrega a orientação (evita a
     * mesma instrução em dois blocos do mesmo prompt). Draft mantém o
     * default `true`.
     */
    fun buildContextBlock(
        dossier: Dossier,
        includeSpeakerNote: Boolean = true,
        /** exp/chat-diagnostics (H5): remove o SPEECH_MENTOR_CHAT_HINT. */
        includeMentorHint: Boolean = true,
    ): String
}

/**
 * Implementação default: prompt estruturado em seções markdown-ish
 * (`## SEÇÃO ATUAL`, `## TEXTOS BÍBLICOS`, …) com degradação graciosa
 * (seções vazias são omitidas).
 *
 * @param refCharLimit teto de chars por texto de ref (trunca com " […]").
 */
class DefaultDossierPromptBuilder(
    private val refCharLimit: Int = 1000,
) : DossierPromptBuilder {

    override fun build(dossier: Dossier): String {
        return buildString {
            appendLine(HEADER.trimIndent())
            appendLine()
            // Draft (JSON): a persona vem do system prompt do provider; o
            // bloco de chat "MODO MENTOR" não entra aqui.
            appendContext(dossier, includeMentorHint = false)
            appendTask(dossier)
            appendJsonFormat()
        }.trim()
    }

    override fun buildContextBlock(
        dossier: Dossier,
        includeSpeakerNote: Boolean,
        includeMentorHint: Boolean,
    ): String {
        return buildString {
            appendContext(
                dossier,
                includeSpeakerNote = includeSpeakerNote,
                includeMentorHint = includeMentorHint,
            )
        }.trim()
    }

    private fun StringBuilder.appendContext(
        d: Dossier,
        includeSpeakerNote: Boolean = true,
        includeMentorHint: Boolean = false,
    ) {
        appendDiscourse(d)
        if (includeMentorHint) appendMentorHint(d)
        appendDemoFormat(d)
        appendS38Guidelines(d)
        if (includeSpeakerNote) appendSpeakerNote(d)
        appendSection(d)
        appendAgreedApproach(d)
        appendOverview(d)
        appendBibleTexts(d)
        appendPublicationTexts(d)
        appendMethodPrinciples(d)
        appendTransition(d)
        appendUnresolved(d)
    }

    /**
     * Fase A — princípio de MENTORIA no chat: a primeira resposta orienta;
     * o texto só sai quando o usuário pedir. Só entra no contextBlock (chat),
     * nunca no prompt JSON de draft.
     */
    private fun StringBuilder.appendMentorHint(d: Dossier) {
        val type = d.discourseType ?: return
        val isSpeech = type == DiscourseType.S34_DISCOURSE.name ||
            type == DiscourseType.TREASURES_TALK.name ||
            type == DiscourseType.MINISTRY_TALK.name ||
            type == DiscourseType.AVULSO.name
        // Texto livre também é persistido como S34_DISCOURSE (uma única seção
        // "Corpo"); não trate uma nota avulsa como discurso. `overview` vazio
        // = dossiê de teste/legado: mantém a orientação (comportamento seguro).
        val structuredSpeech = d.overview.isEmpty() || d.overview.size > 1 ||
            type == DiscourseType.TREASURES_TALK.name ||
            type == DiscourseType.MINISTRY_TALK.name
        val hint = when {
            type == DiscourseType.MINISTRY_PART.name ->
                GenerationPrompts.DEMO_SCRIPTWRITER_CHAT_HINT
            isSpeech && structuredSpeech -> GenerationPrompts.SPEECH_MENTOR_CHAT_HINT
            else -> return
        }
        appendLine(hint)
        val minutes = d.currentSection.minutes
        if (minutes > 0) {
            if (type == DiscourseType.MINISTRY_PART.name) {
                appendLine(
                    "Duração da seção: $minutes min ≈ " +
                        "${SpeechPacing.targetTurns(minutes)} réplicas.",
                )
            } else {
                appendLine(
                    "Duração da seção: $minutes min ≈ " +
                        "${SpeechPacing.targetWords(minutes)} palavras.",
                )
            }
        }
        appendLine()
    }

    // ---------- seções ----------

    /**
     * C (título/tipo no system): cabeçalho do discurso — só quando AMBOS
     * presentes (sem cabeçalho órfão). Viaja no contextBlock, que compõe o
     * system prompt em todos os providers.
     */
    private fun StringBuilder.appendDiscourse(d: Dossier) {
        val title = d.noteTitle?.takeIf { it.isNotBlank() } ?: return
        val type = d.discourseType?.takeIf { it.isNotBlank() } ?: return
        appendLine("## DISCURSO")
        // G: o S-34 carrega o "# " do markdown — limpa para o prompt.
        appendLine("Título: ${title.removePrefix("# ").trimStart()}")
        appendLine("Tipo: $type")
        appendLine()
    }

    /**
     * UX de demonstrações (V&M): instrução de formato de diálogo — só no
     * MINISTRY_PART (as falas viram `<p><b>Papel:</b> texto</p>`, sem listas).
     */
    private fun StringBuilder.appendDemoFormat(d: Dossier) {
        // Fase A: MINISTRY_TALK (parte 6 "Discurso") NÃO é diálogo.
        if (d.discourseType != DiscourseType.MINISTRY_PART.name) {
            return
        }
        appendLine("## FORMATO DE DEMONSTRAÇÃO")
        appendLine(
            "Você está ajudando a escrever um diálogo. Formate as falas usando " +
                "<p><b>Papel:</b> texto</p>. Não converta em listas."
        )
        val minutes = d.currentSection.minutes
        if (minutes > 0) {
            appendLine(
                "Duração alvo: $minutes min ≈ ${SpeechPacing.targetTurns(minutes)} réplicas. " +
                    "Falas curtas (máx. 3 frases por turno)."
            )
        }
        appendLine()
    }

    /**
     * S-38: diretrizes da reunião para partes V&M.
     *
     * Fase A: vale para MINISTRY_PART (demonstração) e MINISTRY_TALK (parte 6
     * "Discurso"). O bloco de formato muda: demonstração = diálogo curto;
     * discurso = fala contínua de mentor, com meta de palavras.
     *
     * "Colar e Pronto" v2: a descrição (cenário + referência) vira o título do
     * card e o corpo guarda só o diálogo; notas antigas mantêm a descrição no
     * corpo. Cenário/referência saem do título + corpo. O tipo da parte vive no
     * título da nota ("Cultivando o interesse (4 min)"); fallback no título da
     * seção (notas antigas).
     */
    private fun StringBuilder.appendS38Guidelines(d: Dossier) {
        val isDemo = d.discourseType == DiscourseType.MINISTRY_PART.name
        val isTalk = d.discourseType == DiscourseType.MINISTRY_TALK.name
        if (!isDemo && !isTalk) return
        val section = d.currentSection
        val description = buildString {
            append(section.title)
            if (section.contentHtml.isNotBlank()) append(' ').append(section.contentHtml)
        }
        // "Cultivando o interesse (4 min)" -> "Cultivando o interesse"
        val partTypeFromNote = d.noteTitle.orEmpty()
            .replace(Regex("\\s*\\(\\d+\\s*min\\)\\s*$"), "")
            .trim()
        val scenario = com.bettertalker.app.data.util.vmScenarioOf(description)
        val reference = com.bettertalker.app.data.util.vmReferenceOf(description)
        appendLine("## DIRETRIZES DA REUNIÃO (S-38)")
        (s38GuidanceFor(section.title) ?: s38GuidanceFor(partTypeFromNote))
            ?.let { appendLine(it) }
        scenario?.let {
            appendLine("Cenário: ${it.lowercase().replaceFirstChar { c -> c.uppercase() }}")
        }
        appendLine(
            buildString {
                if (isTalk) {
                    append("Esta é uma parte de DISCURSO da reunião (fala contínua, não diálogo). ")
                    if (section.minutes > 0) {
                        append(
                            "Duração alvo: ${section.minutes} min ≈ " +
                                "${SpeechPacing.targetWords(section.minutes)} palavras. "
                        )
                    }
                } else {
                    append("Siga o formato desta parte da reunião (não faça um discurso longo). ")
                }
                val focus = if (isTalk) "este tema" else "este cenário"
                if (reference != null) {
                    append("Se o texto de $reference estiver nas FONTES, use-o; ")
                    append("se não, foque em princípios práticos de oratória para $focus.")
                } else {
                    append("Se a publicação da parte não estiver nas FONTES, ")
                    append("foque em princípios práticos de oratória para $focus.")
                }
            }
        )
        appendLine()
    }

    /**
     * F5a: persona do orador (NOTA do esboço). Impressa UMA vez no
     * contextBlock; quando o chat também carrega o bloco estrutural do S-34,
     * cada canal mostra a sua versão rotulada (draft/mini discurso recebem
     * só esta).
     */
    private fun StringBuilder.appendSpeakerNote(d: Dossier) {
        val note = d.speakerNote?.takeIf { it.isNotBlank() } ?: return
        appendLine("## ORIENTAÇÃO DO ORADOR (do esboço)")
        appendLine(note.trim())
        appendLine()
    }

    private fun StringBuilder.appendSection(d: Dossier) {
        appendLine("## SEÇÃO ATUAL")
        appendLine("Título: ${d.currentSection.title}")
        appendLine("Role: ${d.currentSection.role.name}")
        appendLine("Minutos: ${d.currentSection.minutes}")
        // F2.3: objetivo do tópico (quando houver)
        d.currentSection.objective?.takeIf { it.isNotBlank() }?.let {
            appendLine("Objetivo: $it")
        }
        val subText = d.currentSubPoint?.outlineText?.takeIf { it.isNotBlank() }
            ?: "(cursor na seção)"
        appendLine("Sub-ponto: $subText")
        d.currentSubPoint?.instruction?.let {
            appendLine("Instrução: $it")
        }
        // F2.3: a linha de raciocínio inteira do tópico (todos os sub-pontos).
        val line = d.sectionSubPoints
            .filter { it.outlineText.isNotBlank() }
            .sortedBy { it.order }
            .mapIndexed { i, sp -> "${i + 1}. ${sp.outlineText.trim()}" }
        if (line.isNotEmpty()) {
            appendLine("Linha de raciocínio do tópico (partes de UMA ideia — não trate como textos separados):")
            line.forEach { appendLine(it) }
        }
        // B (prosa no dossiê): metadados acima; aqui o que já foi escrito.
        appendSectionProse(d)
        appendLine()
    }

    /**
     * B1/B2/B3 — prosa da seção em texto simples, sempre nesta ordem;
     * cada bloco omitido quando o campo estiver vazio/nulo.
     */
    private fun StringBuilder.appendSectionProse(d: Dossier) {
        val developed = d.sectionSubPoints.sortedBy { it.order }
            .filter { it.developedHtml.isNotBlank() }
        if (d.currentSection.contentHtml.isBlank() && developed.isEmpty() &&
            d.selectedText.isNullOrBlank()
        ) return
        // G: linha em branco separando a prosa do bloco anterior.
        appendLine()
        // B1: texto atual da seção (mini discurso do tópico).
        d.currentSection.contentHtml.takeIf { it.isNotBlank() }?.let {
            appendLine("## TEXTO ATUAL DA SEÇÃO (o que já foi escrito)")
            appendLine(truncateProse(htmlToPlainText(it).trim(), MAX_SECTION_PROSE_CHARS))
        }
        // B2: desenvolvimento atual de cada sub-ponto, em ordem.
        d.sectionSubPoints.sortedBy { it.order }.forEachIndexed { i, sp ->
            sp.developedHtml.takeIf { it.isNotBlank() }?.let {
                appendLine("### Sub-ponto ${i + 1} — desenvolvimento atual")
                appendLine(truncateProse(htmlToPlainText(it).trim(), MAX_SUBPOINT_PROSE_CHARS))
            }
        }
        // B3: trecho selecionado no editor.
        d.selectedText?.takeIf { it.isNotBlank() }?.let {
            appendLine("## TRECHO SELECIONADO")
            appendLine(truncateProse(htmlToPlainText(it).trim(), MAX_SUBPOINT_PROSE_CHARS))
        }
    }

    /** Corta a prosa no teto, marcando o corte (nunca devolve texto parcial calado). */
    private fun truncateProse(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "… [truncado]"

    /**
     * F2.3: abordagem acordada — instrução de alta prioridade para a redação
     * do mini discurso. Só existe quando o usuário confirmou o consenso.
     */
    private fun StringBuilder.appendAgreedApproach(d: Dossier) {
        val approach = d.currentSection.agreedApproach?.takeIf { it.isNotBlank() } ?: return
        appendLine("## ABORDAGEM ACORDADA (instrução de ALTA PRIORIDADE)")
        appendLine(approach)
        appendLine("Siga esta abordagem — ela foi acordada com o usuário.")
        appendLine()
    }

    private fun StringBuilder.appendOverview(d: Dossier) {
        // Enxuto por role (F2b): BODY vê só a seção alvo (já detalhada em
        // ## SEÇÃO ATUAL); INTRO/CONCLUSION veem o resumido de todas.
        if (d.currentSection.role == SectionRole.BODY) return
        if (d.overview.isEmpty()) return
        appendLine("## ESTRUTURA DO DISCURSO")
        d.overview.sortedBy { it.order }.forEach { m ->
            appendLine("- [${m.role.name}] ${m.title} (${m.minutes} min)")
            // F2.3: visão global — objetivo + recorte do que já foi desenvolvido.
            m.objective?.let { appendLine("    objetivo: $it") }
            m.snippet?.let { appendLine("    já desenvolvido: $it") }
        }
        appendLine()
    }

    private fun StringBuilder.appendBibleTexts(d: Dossier) {
        val usable = d.bibleTexts.filter {
            it.text != null && (it.status == ReferenceStatus.RESOLVED ||
                it.status == ReferenceStatus.PARTIAL)
        }
        if (usable.isEmpty()) return
        appendLine("## TEXTOS BÍBLICOS (citação literal autorizada)")
        usable.forEach { bt ->
            val marker = if (bt.status == ReferenceStatus.PARTIAL) " (aprox.)" else ""
            appendLine("- ${bt.ref}$marker: \"${truncate(bt.text!!)}\"")
        }
        appendLine()
    }

    private fun StringBuilder.appendPublicationTexts(d: Dossier) {
        val usable = d.publicationTexts.filter {
            it.text != null && (it.status == ReferenceStatus.RESOLVED ||
                it.status == ReferenceStatus.PARTIAL)
        }
        if (usable.isEmpty()) return
        appendLine("## TRECHOS DE PUBLICAÇÕES (citação literal autorizada)")
        usable.forEach { pt ->
            val marker = if (pt.status == ReferenceStatus.PARTIAL) " (aprox.)" else ""
            val ref = buildString {
                append(pt.ref.symbol)
                pt.ref.page?.let { append(" p. $it") }
                pt.ref.paragraph?.let { append(" §$it") }
            }
            appendLine("- $ref$marker: \"${truncate(pt.text!!)}\"")
        }
        appendLine()
    }

    private fun StringBuilder.appendMethodPrinciples(d: Dossier) {
        if (d.methodPrinciples.isEmpty()) return
        appendLine("## COMO APRESENTAR (princípios de oratória — NÃO É FONTE FACTUAL)")
        d.methodPrinciples.forEach { appendLine("- $it") }
        appendLine()
    }

    private fun StringBuilder.appendTransition(d: Dossier) {
        if (d.currentSection.role == SectionRole.BODY) return
        val t = d.transitionContext ?: return
        appendLine("## TRANSIÇÃO SUGERIDA (resumo do que já foi desenvolvido — NÃO COPIAR)")
        appendLine(t)
        appendLine()
    }

    private fun StringBuilder.appendUnresolved(d: Dossier) {
        if (d.unresolvedRefs.isEmpty()) return
        appendLine("## NÃO CITE ESTAS REFERÊNCIAS (não encontradas no acervo local)")
        d.unresolvedRefs.forEach { appendLine("- $it") }
        appendLine("Se precisar mencioná-las, faça como ref do esboço, sem inventar conteúdo.")
        appendLine()
    }

    private fun StringBuilder.appendTask(d: Dossier) {
        appendLine("## TAREFA")
        val hasSubPoint = d.currentSubPoint != null
        val minutes = d.currentSection.minutes
        when (d.currentSection.role) {
            SectionRole.BODY -> appendLine(
                if (hasSubPoint) {
                    bodySubPointTask(
                        targetWords = subPointTargetWords(minutes, d.sectionSubPoints.size),
                    )
                } else {
                    bodySectionTask(targetWords = targetWordsForMinutes(minutes))
                }
            )
            SectionRole.INTRO -> appendLine(introTask(targetWordsForMinutes(minutes)))
            SectionRole.CONCLUSION -> appendLine(conclusionTask(targetWordsForMinutes(minutes)))
        }
        appendLine()
    }

    private fun StringBuilder.appendJsonFormat() {
        appendLine("## FORMATO DE SAÍDA")
        appendLine("Responda APENAS com JSON válido, sem markdown:")
        appendLine("""{"text": "<p>...</p><p>...</p>", "usedSources": ["ref1", "ref2"]}""")
        appendLine()
        appendLine("- \"text\": HTML simples com <p> por parágrafo. Pode usar <strong>, <em>.")
        appendLine("- \"usedSources\": lista das refs do material acima que foram efetivamente usadas.")
        appendLine("  Se nenhuma, use [].")
    }

    // ---------- helpers ----------

    private fun truncate(text: String): String =
        if (text.length <= refCharLimit) text
        else text.take(refCharLimit) + " […]"

    private companion object {
        val HEADER = """
            Gere APENAS o texto da seção indicada. NÃO invente fatos, citações,
            referências ou doutrina. Use SOMENTE o material fornecido abaixo.
        """

        val BODY_SUBPOINT_BULLETS = """
            - Use os textos bíblicos literalmente quando citar.
            - NÃO repita o outlineText literalmente; desenvolva a ideia.
            - Se um texto bíblico não foi fornecido, NÃO invente; escreva
              "[desenvolver com base em {ref}]".
            - Se estiver se aproximando do limite, encerre a frase atual —
              nunca deixe texto cortado no meio de uma palavra.
        """

        val BODY_SECTION_BULLETS = """
            - Use os textos bíblicos literalmente quando citar.
            - NÃO repita o título literalmente; desenvolva a ideia.
            - Se um texto bíblico não foi fornecido, NÃO invente; escreva
              "[desenvolver com base em {ref}]".
            - Se estiver se aproximando do limite, encerre a frase atual —
              nunca deixe texto cortado no meio de uma palavra.
        """

        fun bodySubPointTask(targetWords: Int?): String =
            "Desenvolva o sub-ponto acima em ${goalLine(targetWords)}.\n" +
                BODY_SUBPOINT_BULLETS.trimIndent()

        fun bodySectionTask(targetWords: Int?): String =
            "Desenvolva a seção acima em ${goalLine(targetWords)}.\n" +
                BODY_SECTION_BULLETS.trimIndent()

        fun introTask(targetWords: Int?): String = """
            Escreva uma abertura (${goalLine(targetWords)}) que:
            1. Capture a atenção (gancho ou pergunta).
            2. Apresente o tema.
            3. Transicione para o primeiro ponto.
            - Use os textos bíblicos literalmente se citar.
            - NÃO cubra argumentos dos pontos do corpo.
            - Se estiver se aproximando do limite, encerre a frase atual —
              nunca deixe texto cortado no meio de uma palavra.
        """.trimIndent()

        fun conclusionTask(targetWords: Int?): String = """
            Escreva um fechamento (${goalLine(targetWords)}) que:
            1. Recapitule brevemente os pontos principais.
            2. Aplique à vida da assistência.
            3. Termine com um convite ou chamada.
            - Use os textos bíblicos literalmente se citar.
            - NÃO introduza argumentos novos.
            - Se estiver se aproximando do limite, encerre a frase atual —
              nunca deixe texto cortado no meio de uma palavra.
        """.trimIndent()

        /** Meta proporcional → "cerca de N palavras"; sem minutos → parágrafos. */
        fun goalLine(targetWords: Int?): String =
            if (targetWords != null) "cerca de $targetWords palavras"
            else "2-3 parágrafos curtos"
    }
}

/**
 * P0 (alinhamento persona/tempo): meta de palavras proporcional ao tempo de
 * fala do tópico (~130 palavras/min — mesmo ritmo do editor/WordCount).
 * Sem minutos (null ou ≤ 0) → null: a tarefa usa o fallback em parágrafos.
 * Puro/testável.
 */
internal fun targetWordsForMinutes(minutes: Int?): Int? =
    minutes?.takeIf { it > 0 }?.let { SpeechPacing.targetWords(it) }

/**
 * Meta do sub-ponto: fatia proporcional do tempo da seção entre os
 * sub-pontos (piso 80 palavras para não pedir fragmentos irrisórios).
 * Puro/testável.
 */
internal fun subPointTargetWords(sectionMinutes: Int?, subPoints: Int): Int? =
    targetWordsForMinutes(sectionMinutes)
        ?.div(subPoints.coerceAtLeast(1))
        ?.coerceAtLeast(80)

/**
 * Diretriz S-38 por tipo de parte da reunião (substring normalizada —
 * acentos/caixa indiferentes). null = sem diretriz específica (o bloco sai
 * com a instrução geral). Puro/testável.
 */
internal fun s38GuidanceFor(partType: String): String? {
    val t = com.bettertalker.app.data.util.normalizeText(partType)
    return when {
        t.contains("iniciando conversas") || t.contains("iniciar conversas") ||
            t.contains("comece uma conversa") || t.contains("inicie uma conversa") ->
            "O objetivo é iniciar uma conversa amigável, despertar interesse e " +
                "deixar um assunto para a próxima visita. Use um tom natural e não faça discursos."
        t.contains("cultivando o interesse") || t.contains("cultivar o interesse") ||
            t.contains("cultive o interesse") ->
            "O objetivo é cultivar o interesse demonstrado anteriormente, usando " +
                "uma pergunta de transição e oferecendo uma publicação."
        t.contains("fazendo discipulos") || t.contains("fazer discipulos") ||
            t.contains("faca discipulos") ->
            "O objetivo é ajudar a pessoa a progredir rumo ao batismo — use a " +
                "Bíblia com respeito e ofereça um estudo bíblico."
        t.contains("explicando suas crencas") || t.contains("explicar as suas crencas") ||
            t.contains("explicar suas crencas") ->
            "O objetivo é explicar uma crença de forma simples e respeitosa, " +
                "com base na Bíblia, sem entrar em discussão."
        else -> null
    }
}
