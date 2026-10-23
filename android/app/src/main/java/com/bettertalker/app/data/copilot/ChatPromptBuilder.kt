package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.data.util.normalizeText

/**
 * Fase 16 — paridade do chat nativo com a F15 (web).
 *
 * Reproduz o contrato de prompt do `llmPrompt` web: as mesmas regras de
 * fidelidade, a mesma separação CONTENT/TRAINING e os mesmos limites. É a
 * fronteira declarada da Fase 16 — o texto não é compartilhado com o TypeScript
 * porque o provider nativo consome um prompt único, mas o COMPORTAMENTO é o
 * mesmo, e os testes travam esse contrato.
 *
 * Puro/testável: sem IO, sem Android.
 */

/** Frase exata de insuficiência. O modelo deve repeti-la literalmente (§35 F15). */
const val INSUFFICIENT_EVIDENCE_MESSAGE =
    "Não encontrei suporte suficiente nas fontes disponíveis."

/**
 * v2.7.0 (Opção B): fontes de oratória no CONTEXTO (dinâmico), não na persona
 * estável. Entra só quando a seção em foco é de oratória — o system prompt
 * fica enxuto e as fontes aparecem quando são relevantes.
 */
val ORATORY_SOURCES_BLOCK = """
## FONTES DE ORATÓRIA (referência)
- Estrutura de discurso: BE 39-42 (elaborar esboço), 47-51 (discurso dirigido), 52-55 (discurso público), 166-169 (uso de esboço).
- Ensino, aplicação, conclusão e entrega: TH "Melhore Sua Leitura e Seu Ensino" (20 lições). Este é o título oficial: NUNCA use "Faça o Melhor Uso da Leitura e do Ensino" (título inexistente).
- A TH NÃO ensina a preparar discursos públicos nem a montar esboço; nesses temas, a fonte é o BE.
- Não invente lição/página; sem o trecho no acervo, use o princípio geral.
""".trimIndent()

/**
 * T3 — dica contextual: quando a resposta é a frase de insuficiência, o escopo
 * está sem fontes de conteúdo e há publicações no acervo ainda não vinculadas,
 * sugere vincular pelo "+". Puro/testável.
 */
fun contextualScopeTip(
    responseText: String,
    unlinkedPublications: Int,
    scopeEmpty: Boolean,
): String? =
    if (scopeEmpty && unlinkedPublications > 0 &&
        responseText.contains(INSUFFICIENT_EVIDENCE_MESSAGE)
    ) {
        "\n\nDica: você tem $unlinkedPublications publicação(ões) no acervo. " +
            "Toque no + → Anexar do acervo."
    } else {
        null
    }

/** Fontes de conteúdo no prompt (§ F15 llmPrompt). */
const val MAX_CONTENT_SOURCES = 8

/** Orientações de oratória no prompt. */
const val MAX_TRAINING_SOURCES = 4

/** Orçamento total de fontes (conteúdo + treinamento + legadas). */
const val MAX_TOTAL_SOURCES = 12

/** Limites de serialização, espelhando o web. */
const val MAX_CONTEXT_PASSAGE_CHARS = 800

/**
 * T1 (modo colar e conversar) — teto do texto colado íntegro no prompt.
 * Bem acima do teto de bloco (2000), para o modelo ler o esboço como o
 * usuário escreveu, sem picar via RAG.
 */
const val MINIMAL_PASTE_MAX_CHARS = 20_000

/**
 * T1 (modo colar e conversar) — system minimalista, 3 linhas. Sem regras
 * de S-34/oratória, sem dossiê, sem cauda de formato: o esboço colado É o
 * contexto. T2 (Fase 2.2): o modo criação vira explícito — criar fora do
 * esboço É permitido quando marcado como sugestão. Makeover (mentor):
 * paráfrases e conhecimento geral permitidos; a frase de insuficiência
 * vira último recurso. Puro/testável.
 */
val MINIMAL_SYSTEM_PROMPT = """
Você é um copiloto de oratória. Responda com base no esboço abaixo.
Ao criar conteúdo novo (ilustração, exemplo, metáfora), marque com 〈sugestão〉…〈/sugestão〉 e apresente como sugestão sua.
Se não houver suporte no esboço para fatos, números ou referências, use paráfrases e conhecimento geral de forma criativa.
Use a frase "Não encontrei suporte suficiente nas fontes disponíveis." apenas se não houver nada útil a acrescentar.
""".trimIndent()

/**
 * #18-fix: persona do chat (geral/oratória). ESTÁVEL — sem interpolação
 * dinâmica: entra como PREFIXO do system e preserva o cache determinístico.
 * O modo colar mantém a sua própria (MINIMAL_SYSTEM_PROMPT) — não substituir.
 * Makeover (mentor): de "guarda de trânsito" para mentor proativo — sugere
 * ideias e paráfrases; a recusa vira último recurso (ver CHAT_TAIL_INSTRUCTIONS).
 */
val CHAT_PERSONA_BASE =
    "Você é um orientador experiente em oratória, ajudando oradores a preparar " +
        "discursos e demonstrações nas reuniões das Testemunhas de Jeová. " +
        "Você conhece profundamente a Bíblia, as publicações e a estrutura do S-34. " +
        "Sua comunicação é calorosa, encorajadora e direta, como um instrutor que " +
        "caminha ao lado do orador na preparação. Prefira frases curtas, vocabulário acessível " +
        "e um tom motivador. Seja proativo em sugerir ideias, ilustrações e formas " +
        "criativas de transmitir a mensagem. Evite formalidade excessiva e " +
        "respostas burocráticas. " +
        "Ao citar trechos, cite no máximo uma frase por vez (até 15 palavras). " +
        "Não junte fragmentos dentro das aspas. Se precisar de mais contexto, " +
        "parafraseie fora das aspas. " +
        // exp/chat-quality (2026-10-21): no A/B com o prompt real do app, o
        // modelo trocou "Jeová" por "Deus" na introdução (1 ocorrência em 212
        // palavras). A regra de preservação entra na persona do chat.
        "Preserve os termos bíblicos e publicationais (Jeová, Escrituras, " +
        "publicador, Armagedom): nunca troque 'Jeová' por 'Deus' nem " +
        "'Escrituras' por 'Bíblia'." +
        // v2.6.0: o Copilot é ferramenta de orientação, não membro da
        // congregação. Bloqueia autoidentidade espiritual e tratamento de
        // parentesco espiritual na saída.
        "\n\nVocê é uma ferramenta de orientação: não se apresente como membro " +
        "ou representante da organização; trate o usuário por 'você' ou 'orador'."

/**
 * Persona das rotas de geração/oratória (enxuta, v2.7.0/Opção B).
 * O chat livre usa [CHAT_PERSONA_FREE] — sem guia empilhado.
 */
val CHAT_PERSONA = CHAT_PERSONA_BASE

/**
 * Correção 1 do A/B: o card é para ESCRITA explícita. Explicação/análise/
 * orientação fica na conversa — o modelo não deve embrulhar tudo em
 * `<texto-do-card>` só porque a resposta é organizada.
 */
const val CARD_INTENT_RULE =
    "Use <texto-do-card>...</texto-do-card> SOMENTE quando o usuário pedir " +
        "explicitamente para escrever, redigir, montar ou criar a fala/o texto/o " +
        "diálogo. Para explicar, analisar, listar tópicos ou orientar, responda " +
        "na conversa, SEM card."

/**
 * Versão A do A/B de naturalidade: o chat livre mantém a persona, o contexto,
 * o S-34 e os marcadores, mas não empilha o guia de linguagem de geração.
 * A regra de intenção do card entra só no chat livre, não na geração.
 */
val CHAT_PERSONA_FREE = CHAT_PERSONA_BASE + "\n\n" + CARD_INTENT_RULE

/**
 * P0 (alinhamento persona/tempo): linha de voz para os fluxos de GERAÇÃO
 * (oratória e demais escritas) — garante o mesmo tom do chat nos textos
 * gerados, não só na conversa. Prefixada com quebra de linha para emendar
 * direto após uma persona.
 */
val GENERATION_VOICE_LINE =
    "\nAo escrever discursos e demonstrações, mantenha o tom caloroso e " +
        "direto de um orientador experiente que caminha ao lado do orador. " +
        "Use um ritmo natural de fala." +
        "\nSiga também as DIRETRIZES DE LINGUAGEM do system: simplicidade, " +
        "voz ativa e termos bíblicos preservados (Jeová, as Escrituras, publicador)."

/**
 * T1 (Fase 2.1) — teto do bloco de memória injetado no modo colar. O system
 * segue minimalista: a memória entra como CONTEÚDO, junto da mensagem.
 */
const val MINIMAL_MEMORY_MAX_CHARS = 400

/**
 * T1 (Fase 2.1) — prefixo de conteúdo com a memória da construção para a
 * mensagem do modo colar. Bloco nulo/vazio → `""` (prompt inalterado). Bloco
 * maior que [MINIMAL_MEMORY_MAX_CHARS] corta na última linha inteira + "…"
 * (o resultado nunca passa do teto). Puro/testável.
 */
fun minimalMemoryPrefix(memoryBlock: String?): String {
    val block = memoryBlock?.trim().orEmpty()
    if (block.isEmpty()) return ""
    val fitted = if (block.length <= MINIMAL_MEMORY_MAX_CHARS) {
        block
    } else {
        val cut = block.take(MINIMAL_MEMORY_MAX_CHARS - 1)
        val lastNewline = cut.lastIndexOf('\n')
        val head = if (lastNewline > 0) cut.take(lastNewline) else cut
        head.trimEnd() + "…"
    }
    return "$fitted\n\n"
}

/**
 * T1 (modo colar e conversar) — prompt mínimo: system fixo + esboço
 * íntegro (truncado no teto) + mensagem. Sem TAIL, sem regras, sem RAG.
 * Puro/testável.
 */
fun buildMinimalPastePrompts(
    pastedText: String,
    message: String,
    history: List<ChatTurn>,
): Pair<String, String> {
    val pasted = pastedText.take(MINIMAL_PASTE_MAX_CHARS)
    val user = chatBriefToText(message, history, history.none { it.fromMe }) +
        "\n\n--- ESBOÇO COLADO (íntegro; única fonte) ---\n$pasted\n--- FIM DO ESBOÇO ---"
    return MINIMAL_SYSTEM_PROMPT to user
}

/**
 * T2 (Fase 2) — instrução de materialização: pede ao modelo a estrutura
 * completa discutida, em Markdown canônico que o [StructureParser] entende.
 * O histórico da conversa (com a memória da construção) é a fonte.
 *
 * T1 (Fase 2.2): o esboço colado complementa o histórico (ver
 * [applyStructureMessage]) e o conteúdo tem de ser REAL — os colchetes do
 * formato são só a marcação do esquema, nunca placeholders.
 */
const val APPLY_STRUCTURE_INSTRUCTION =
    "Gere a estrutura COMPLETA e final do discurso a partir do esboço original " +
        "(quando houver) e das decisões desta conversa, em Markdown canônico, " +
        "exatamente neste formato:\n" +
        "# INTRODUÇÃO\n[texto da abertura]\n\n" +
        "# SEÇÃO 1: [título do ponto]\n[texto do ponto]\n\n" +
        "# CONCLUSÃO\n[texto do fechamento]\n\n" +
        "Use uma linha # SEÇÃO N para cada ponto desenvolvido. " +
        "Os colchetes acima são só o formato — não os copie: escreva o conteúdo " +
        "real de cada parte, sem placeholders nem notas. " +
        "Escreva APENAS o texto que o orador deve falar. NÃO inclua instruções " +
        "meta como \"Abra com…\", \"Comece dizendo…\" ou \"Use uma pergunta…\" " +
        "dentro das seções: orientação de técnica não é discurso. " +
        "Responda SOMENTE com a estrutura, sem explicações fora dela."

/**
 * T1 (Fase 2.2) — mensagem do "insira tudo": a instrução canônica + o esboço
 * colado (quando existir), no mesmo canal de mensagem comum a todos os
 * providers. O esboço COMPLEMENTA o histórico; sem esboço (modo estruturado),
 * a mensagem é a instrução pura (comportamento anterior). Teto do esboço:
 * [MINIMAL_PASTE_MAX_CHARS]. Puro/testável.
 */
fun applyStructureMessage(pastedOutline: String?): String {
    val esboco = pastedOutline?.trim().orEmpty()
    if (esboco.isEmpty()) return APPLY_STRUCTURE_INSTRUCTION
    val clipped = esboco.take(MINIMAL_PASTE_MAX_CHARS)
    return APPLY_STRUCTURE_INSTRUCTION +
        "\n\n## ESBOÇO ORIGINAL (colado pelo usuário)\n$clipped\n## FIM DO ESBOÇO ORIGINAL"
}

/**
 * T1 — marcador de criação: o modelo envolve ilustrações/metáforas/exemplos
 * originais em 〈sugestão〉…〈/sugestão〉. O gate isenta a PROSA marcada, mas
 * NUNCA número nem referência inventados.
 */
const val SUGGESTION_MARKER_RULE =
    "MODO CRIAÇÃO: ao criar ilustração, metáfora, analogia ou exemplo original, envolva o " +
        "trecho criado em 〈sugestão〉…〈/sugestão〉 para o sistema reconhecer a criação e não " +
        "tratá-la como fato. O marcador isenta prosa criativa — números e referências " +
        "continuam exigindo apoio real no acervo."

/**
 * T3 (higiene) — removido: era código morto no Android (nenhum provider o
 * enviava; todos usam buildChatPrompt/buildDeepSeekPrompts). O contrato de
 * fidelidade vive no prompt real e é travado por promptVivoTemAsRegrasDeFidelidade.
 */

/** Bloco em foco. Vazio se não há bloco nem duração (§ F15 blockSection). */
fun blockSection(blockTitle: String?, blockMinutes: Int?): String {
    if (blockTitle.isNullOrBlank() && blockMinutes == null) return ""
    return "\n\n--- BLOCO ATUAL ---\n" +
        "Título: ${if (blockTitle.isNullOrBlank()) "Sem título" else blockTitle}\n" +
        "Duração: ${blockMinutes ?: "?"} minutos\n" +
        "--- FIM DO BLOCO ---\n"
}

/** Categoria serializável; desconhecida nunca vira categoria inventada. */
private fun categorySerial(c: TrainingCategory?): String = c?.serial ?: TrainingCategory.UNKNOWN.serial

/**
 * exp/chat-quality (2026-10-21): rótulo da fonte para o prompt.
 *
 * A `reference` do acervo é "símbolo seção §N" com N = posição SEQUENCIAL da
 * passagem (dava "w25.07 Sua resposta §424" — número que não existe na
 * revista). Quando a fonte tem página/parágrafo REAIS (o índice T4 do JWPUB),
 * o rótulo passa a ser "w25.07 Sua resposta pág. 22 §8" — o mesmo formato da
 * citação do usuário. Puro/testável.
 */
fun sourceLabel(source: com.bettertalker.app.data.domain.EvidenceSource): String {
    val page = source.page
    val par = source.paragraph
    if (page == null && par == null) return source.reference
    // O "§N" da reference é a posição SEQUENCIAL da passagem (dava
    // "w25.07 Sua resposta §605" — número que não existe na revista). Com
    // página/parágrafo REAIS, o rótulo vira "w25.07 Sua resposta pág. 22 §8".
    val base = source.reference.replace(Regex("""\s*§\d+\s*$"""), "").trim()
    val parts = mutableListOf(base)
    if (page != null) parts += "pág. $page"
    if (par != null) parts += "§$par"
    return parts.filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * Serializa o ContextPack em dois blocos explicitamente separados, para que
 * conteúdo e treinamento nunca se confundam no prompt. Trechos antigos (sem
 * metadados estruturados) entram como "legado" e completam o orçamento.
 *
 * Puro/testável.
 */
fun serializePack(
    pack: ContextPack,
    legacyPassages: List<String> = emptyList(),
    structural: OutlineStructureContext? = null,
    oratory: OratoryStructure.Inferred? = null
): String {
    val content = pack.contentSources.take(MAX_CONTENT_SOURCES)
    val training = pack.trainingSources.take(MAX_TRAINING_SOURCES)
    val legacy = legacyPassages
        .filter { it.isNotBlank() }
        .take((MAX_TOTAL_SOURCES - content.size - training.size).coerceAtLeast(0))
        .map { it.take(MAX_CONTEXT_PASSAGE_CHARS) }

    var section = ""
    // F19-B.5: o S-34 vem PRIMEIRO e identificado — a estrutura não se perde
    // no meio de resultados de similaridade (§22).
    structural?.let { section += serializeStructuralContext(it) }
    // F20-A: planejamento oratório derivado, logo após a estrutura do S-34.
    oratory?.let { section += serializeOratoryStructure(it) }
    if (content.isNotEmpty() || legacy.isNotEmpty()) {
        val lines = buildList {
            content.forEachIndexed { i, s ->
                add("[Fonte ${i + 1}: ${sourceLabel(s)}] ${s.text}")
            }
            legacy.forEachIndexed { i, p ->
                add("[Fonte ${content.size + i + 1}] $p")
            }
        }
        section += "\n\n--- FONTES DE CONTEÚDO (base para afirmações factuais) ---\n" +
            lines.joinToString("\n\n") + "\n--- FIM DAS FONTES DE CONTEÚDO ---\n"
    }
    if (training.isNotEmpty()) {
        val lines = training.mapIndexed { i, s ->
            "[Orientação ${i + 1}: ${s.reference} | técnica: ${categorySerial(s.trainingCategory)}] ${s.text}"
        }
        section += "\n\n--- ORIENTAÇÕES DE ORATÓRIA (técnica de apresentação; NÃO usar como fatos) ---\n" +
            lines.joinToString("\n\n") + "\n--- FIM DAS ORIENTAÇÕES ---\n"
    }
    if (pack.unavailableRefs.isNotEmpty()) {
        section += "\n\n--- REFERÊNCIAS DO ESBOÇO SEM TEXTO NO ACERVO (não citar como texto) ---\n" +
            pack.unavailableRefs.distinct().joinToString("\n") { "- $it" } +
            "\n--- FIM DAS REFERÊNCIAS ---\n"
    }
    if (section.isEmpty()) {
        section = "\n\n(Nenhuma fonte do acervo local foi recuperada para este bloco. " +
            "Não se limite a recusar. Use sua base geral de oratória, conhecimento bíblico " +
            "e ilustrações práticas para enriquecer a conversa. Deixe claro o que é " +
            "sua sugestão ou conhecimento geral, e use a frase de insuficiência APENAS " +
            "se for absolutamente impossível ajudar com o tema.)\n"
    }
    return section
}

/**
 * Linha de foco. Verificação factual rebaixa o treinamento a zero autoridade:
 * é a regra que impede BE/TH virar prova. Puro/testável.
 */
fun focusLine(message: String): String {
    val m = message.lowercase()
    val verifying = VerifyingCues.any { m.contains(it) }
    return if (verifying) {
        "Foco: conferir se a informação tem apoio nas FONTES DE CONTEÚDO autorizadas. " +
            "Não use orientações de oratória como prova factual."
    } else {
        "Foco: responder à mensagem do usuário usando o contexto e as fontes disponíveis. " +
            "Orientações de oratória orientam o COMO apresentar; fontes de conteúdo, o O QUÊ."
    }
}

/** Pistas de verificação. Mesma família do `inferIntent`, casada no texto cru. */
private val VerifyingCues = listOf(
    "verifi", "confira", "está correta", "está certo", "realmente", "tem apoio", "suporte", "publica"
)

/**
 * T2 — cauda de instruções do chat (estável entre turnos). Extraída para ser
 * reutilizada pelo prompt do DeepSeek sem duplicar texto.
 *
 * T2 — cauda de instruções do chat (marcadores, fontes e segurança).
 * A Versão A do A/B removeu o molde markdown e a detecção de intenção;
 * a conversa não é forçada a formatar. Puro/testável.
 */
const val CHAT_TAIL_INSTRUCTIONS: String =
    "Responda de forma conversacional e direta: parágrafos curtos, sem rótulos internos " +
        "(nada de \"ANÁLISE DE INTENÇÃO\", \"CONTEXTO:\" ou \"RESPOSTA:\"). Se a melhor ajuda for sugerir um novo texto para o " +
        "bloco, apresente-o claramente como sugestão — nunca como algo já aplicado. Se faltar " +
        "suporte factual do acervo, ofereça paráfrases, princípios ou ilustrações criativas, " +
        "marcando como sugestão sua. Apenas como ÚLTIMO RECURSO (quando não houver nada a dizer), " +
        "use exatamente a frase de insuficiência. " +
        // T3 (higiene): marcador em 1 parágrafo único (antes eram a regra +
        // o parágrafo das aspas). Números e referências seguem exigindo apoio.
        "\nAo criar algo seu (ilustração, metáfora, exemplo), envolva-o em " +
        "〈sugestão〉…〈/sugestão〉 ANTES de qualquer aspas, para o sistema reconhecer " +
        "a criação e não tratá-la como fato — números e referências continuam " +
        "exigindo apoio real no acervo." +
        // T4 (acesso bíblico): o versículo resolvido é a única fonte autorizada.
        // Makeover: sem o texto no bloco, paráfrase marcada (SEM aspas) em vez
        // de recusa seca — o GroundednessVerifier segue guardando as aspas.
        "\nSe a pergunta citar uma referência bíblica e o bloco TEXTOS BÍBLICOS existir, " +
        "cite o texto do bloco entre aspas, palavra por palavra. Se o texto não existir " +
        "no bloco, NÃO diga apenas que 'não há suporte'. Faça uma paráfrase calorosa do " +
        "princípio bíblico (SEM aspas) e avise que é uma paráfrase." +
        // T2 (expert em publicações): mesma regra para trechos de publicação.
        "\nAo citar um trecho de TRECHOS DE PUBLICAÇÕES, use aspas palavra-por-palavra, " +
        "como para versículos." +
        // T1 (Fase 2): ao registrar um consenso ou decisão com o usuário,
        // marque com 〈decisão〉…〈/decisão〉 para o app lembrar e continuar daqui.
        "\nAo registrar um consenso ou decisão com o usuário, marque com " +
        "〈decisão〉…〈/decisão〉." +
        // #21-fix: distinção texto-aplicável vs conversa — o app destaca o
        // bloco com botão próprio ("Usar no card").
        "\nQuando devolver texto pronto para aplicar no card (discurso, desenvolvimento, " +
        "transição, introdução, conclusão), envolva APENAS esse trecho em " +
        "<texto-do-card>...</texto-do-card>. Explicações, comentários e conversa ficam FORA " +
        "do bloco. Um bloco por resposta (se houver). Se a resposta for só conversa, não use " +
        "o marcador."

/** T2 — verbos/pedidos que pedem resposta estruturada (texto normalizado). */
private val STRUCTURED_HINTS = Regex(
    "\\b(desenvolv|escrev|cri|criar|criacao|sugir|sugest|mont|elabor|detalh|explic|expliq|" +
        "list|roteiro|estrutur|como (faco|fazer|faria|comeco|comecar)|passo a passo|" +
        "topicos|pontos|resum)\\w*\\b"
)

/**
 * T2 — a mensagem pede resposta estruturada? Puro/testável.
 * "desenvolva", "escreva", "crie", "sugira", "como fazer" → sim;
 * "o que acha?", "e se?" → não.
 */
fun wantsStructuredAnswer(message: String): Boolean =
    STRUCTURED_HINTS.containsMatchIn(" ${normalizeText(message)} ")

/**
 * T2 — cauda do chat, sem molde fixo: citação, marcador e insuficiência
 * sempre presentes; a intenção não força formatação. Puro/testável.
 */
fun chatTailInstructions(): String = CHAT_TAIL_INSTRUCTIONS

/**
 * Prompt do chat. Ordem: brief (histórico + continuidade + mensagem), foco,
 * fontes, bloco e o texto em foco. Puro/testável.
 */
fun buildChatPrompt(
    message: String,
    history: List<ChatTurn>,
    isFirstMessage: Boolean,
    pack: ContextPack,
    blockTitle: String?,
    blockMinutes: Int?,
    blockText: String,
    legacyPassages: List<String> = emptyList(),
    structural: OutlineStructureContext? = null,
    oratory: OratoryStructure.Inferred? = null
): String {
    val context = serializePack(pack, legacyPassages, structural, oratory)
    val block = blockSection(blockTitle, blockMinutes)
    val brief = chatBriefToText(message, history, isFirstMessage)
    // F19-B.5: as regras do S-34 entram no prompt quando há estrutura (§12).
    val s34Rules = if (structural != null) "\n$S34_PROMPT_RULES\n" else ""
    // F20-A: a regra da estrutura inferida acompanha o bloco quando existe.
    val oratoryRules = if (oratory != null) "\n$ORATORY_STRUCTURE_RULES\n" else ""
    return "$brief\n\n${focusLine(message)}\n$context$block$s34Rules$oratoryRules\n" +
        "Texto do bloco em foco:\n\"$blockText\"\n\n" +
        chatTailInstructions()
}

/** T2 — par de mensagens do DeepSeek: prefixo estável (system) + volátil (user). */
data class DeepSeekPrompts(val system: String, val user: String)

/**
 * T2 — fontes em ordem ESTÁVEL (por id, nunca por score do retrieval) para o
 * cache persistente da DeepSeek. O ranking continua valendo para seleção; só
 * a serialização do prompt é determinística entre turnos.
 */
fun serializePackStable(
    pack: ContextPack,
    legacyPassages: List<String> = emptyList(),
    structural: OutlineStructureContext? = null,
    oratory: OratoryStructure.Inferred? = null
): String = serializePack(
    pack = ContextPack(
        contentSources = pack.contentSources.sortedBy { it.id },
        trainingSources = pack.trainingSources.sortedBy { it.id }
    ),
    legacyPassages = legacyPassages,
    structural = structural,
    oratory = oratory
)

/**
 * T2 — mensagens do DeepSeek com prefixo estável para o cache persistente
 * (TTL ~72h). O system carrega SÓ o que não muda entre turnos da mesma
 * conversa: regras, S-34, fontes em ordem estável e bloco. O user carrega o
 * volátil: histórico, mensagem, foco e texto do bloco.
 *
 * NUNCA mover campos voláteis (timestamp, contador de turno, mensagem) para o
 * system: qualquer mudança no prefixo invalida o cache e destrói a economia.
 * Puro/testável.
 */
fun buildDeepSeekPrompts(
    message: String,
    history: List<ChatTurn>,
    isFirstMessage: Boolean,
    pack: ContextPack,
    blockTitle: String?,
    blockMinutes: Int?,
    blockText: String,
    legacyPassages: List<String> = emptyList(),
    structural: OutlineStructureContext? = null,
    oratory: OratoryStructure.Inferred? = null,
    contextBlock: String? = null,
): DeepSeekPrompts {
    val context = serializePackStable(pack, legacyPassages, structural, oratory)
    val block = blockSection(blockTitle, blockMinutes)
    val s34Rules = if (structural != null) "\n$S34_PROMPT_RULES\n" else ""
    val oratoryRules = if (oratory != null) "\n$ORATORY_STRUCTURE_RULES\n" else ""
    val dossier = contextBlock?.takeIf { it.isNotBlank() }
        ?.let { "\n## CONTEXTO DO DOSSIÊ (seção em foco no editor)\n$it\n" }
        .orEmpty()
    val sources = if (oratory != null || !contextBlock.isNullOrBlank()) "\n$ORATORY_SOURCES_BLOCK\n" else ""
    // T2 (TAIL condicional): o formato varia com a mensagem — turnos curtos
    // e estruturados geram dois prefixos estáveis (cache por classe).
    val system = (CHAT_PERSONA_FREE + "\n\n" + s34Rules + oratoryRules + context + block + dossier + sources + "\n" +
        chatTailInstructions()).trim()
    val user = chatBriefToText(message, history, isFirstMessage) + "\n\n" + focusLine(message) +
        "\n\nTexto do bloco em foco:\n\"$blockText\""
    return DeepSeekPrompts(system = system, user = user)
}

/**
 * Fase 18 — BLOCO B. Prompt de proposta de edição (porte de
 * `editProposalPrompt` do web). O modelo responde SOMENTE JSON em cerca;
 * o alvo vem do app, nunca do modelo. Puro/testável.
 */
fun buildEditProposalPrompt(
    mode: EditProposalMode,
    text: String,
    tone: String = "ted",
    pack: ContextPack = ContextPack(emptyList(), emptyList()),
    blockTitle: String? = null,
    blockMinutes: Int? = null,
    brief: String = "",
    legacyPassages: List<String> = emptyList()
): String {
    val goal = when (mode) {
        EditProposalMode.REWRITE ->
            "Reescreva INTEGRALMENTE o bloco a seguir com nova versão completa " +
                "(mesma ideia central, forma renovada para palco)."
        EditProposalMode.IMPROVE ->
            "Melhore a clareza e a fluidez do bloco a seguir PRESERVANDO todas as " +
                "ideias originais (sem acrescentar fatos novos)."
        EditProposalMode.INSERT ->
            "Crie um conteúdo NOVO (ilustração, aplicação ou transição, conforme o " +
                "bloco pedir) para inserir APÓS o bloco atual, sem repetir o que já " +
                "está nele. Tom: \"$tone\"."
        EditProposalMode.DELETE ->
            "Proponha a remoção do bloco a seguir (devolva JSON com operação delete)."
        EditProposalMode.SUGGEST ->
            "Sugira melhorias para o bloco a seguir como texto (sem operações de edição)."
    }
    val focus = if (brief.isNotBlank()) "Foco da tarefa (observação): $brief\n" else ""
    val context = serializePack(pack, legacyPassages)
    val block = blockSection(blockTitle, blockMinutes)
    return "$goal\n${focus}Texto do bloco atual:\n\"$text\"\n$context$block\n" +
        "Responda SOMENTE com este JSON em cerca ```json (sem texto fora dela):\n" +
        "{\"explanation\": \"1 frase sobre o que foi proposto\", " +
        "\"operations\": [{\"type\": \"replace\", \"content\": \"<p>...novo bloco integral...</p>\"}]}\n" +
        "Para conteúdo novo use {\"type\": \"insert\", \"position\": \"after\", " +
        "\"content\": \"<p>...</p>\"}. Use HTML simples (p, strong, em). " +
        "Nunca invente fatos, citações ou referências. Se criar ilustração, pergunta " +
        "ou transição, apresente como sugestão do modelo, sem atribuir à fonte. " +
        SUGGESTION_MARKER_RULE
}
