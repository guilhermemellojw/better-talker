package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.util.ChatIntent
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.domain.planning.Audience

/**
 * Fase 20-D — roteamento natural do chat para a sessão oratória.
 *
 * Decide QUAL pipeline atende a mensagem, sem LLM e sem formulário:
 * ```text
 * ORATORY           geração/iteração oratória (F20-A/B/C)
 * STRUCTURAL_QUERY  pergunta sobre a estrutura do S-34 (contexto, sem proposta)
 * PROPOSAL_REPLY    aceitar/rejeitar a proposta pendente (fluxo existente)
 * GENERAL           chat livre de sempre
 * ```
 *
 * Precedência (§5): réplica de proposta → sessão oratória (iteração/herança)
 * → pedido oratório natural → pergunta estrutural → chat geral.
 *
 * O roteador decide o PIPELINE, nunca a veracidade do conteúdo (§36), e não
 * cria score de intenção (§35): são regras explícitas e ordenadas.
 * Puro/testável: sem rede, LLM, banco, UI.
 */
object ChatRouter {

    enum class StructuralTopic { OBJECTIVE, POINTS, SEQUENCE, REFERENCES, ABOUT }

    sealed interface Route {
        data class Oratory(
            val mode: OratoryGeneration.Mode,
            val sectionId: String?,
            val action: OratoryGeneration.Action,
            val inherited: Boolean,
            val reason: String
        ) : Route

        data class StructuralQuery(
            val topic: StructuralTopic,
            val sectionNumber: Int?,
            val reason: String
        ) : Route

        data class ProposalReply(val accept: Boolean, val reason: String) : Route

        /** Refinamento sem geração anterior: nada a refinar (§17). */
        data class NothingToRefine(val message: String, val reason: String) : Route

        /** Pedido que criaria estrutura nova fora do S-34 (§34 F20-C). */
        data class OutOfScope(val requestedPoint: Int, val message: String, val reason: String) : Route

        /**
         * T2 (Fase 2) — "insira tudo na nota": materializa a estrutura
         * construída em conjunto (prévia + persist). Detectado antes do
         * comando de inserção pontual (T3 mini), que sequestraria "na nota".
         */
        data class ApplyStructure(val reason: String) : Route

        /**
         * FALTA-2 — "propõe estrutura para esta seção": a IA devolve um
         * SectionPlan (objetivo, tempo, textos-âncora) para revisão antes
         * da prosa. Detectado antes da oratória (que sequestraria "crie").
         */
        data class ProposeStructure(val reason: String) : Route

        /**
         * F5b (Modo C) — "estruturar como esboço": o usuário colou texto de
         * publicação numa seção e quer que a IA o divida em seções (título,
         * tempos, refs). A aplicação SUBSTITUI as seções (com confirmação).
         */
        data class StructureFromPasted(val reason: String) : Route

        /**
         * F5d (Modo B) — "me dá opções para X": a IA propõe até 3 ângulos
         * (DOCTRINAL/PRACTICAL/NARRATIVE) para um discurso do zero; o usuário
         * escolhe 1 e o fluxo do #5b aplica (substitui a nota). `theme` null
         * = pedido sem tema (o handler pergunta).
         */
        data class AnglesForTheme(val theme: String?, val reason: String) : Route

        /** #8f: discurso curto para Necessidades Locais (sem esboço). */
        data class GenerateLocalNeeds(val inputs: LocalNeedsInputs, val reason: String) : Route

        /**
         * T1 (modo colar e conversar) — conversa sobre um esboço colado
         * íntegro, sem S-34/dossiê/RAG. Construída pelo ViewModel (que sabe
         * se há S-34 vinculado), nunca pelo route() puro.
         */
        data class PasteAndChat(val reason: String) : Route

        data class General(val reason: String) : Route
    }

    /** T1 — texto longo sem estrutura vira candidato a colar-e-conversar. */
    const val PASTE_MIN_CHARS = 500

    /**
     * T1 — candidato a colar-e-conversar: texto corrido longo E vindo do
     * clipboard (#26-fix-b: o tamanho sozinho capturava texto digitado).
     * Puro/testável.
     */
    fun isPasteCandidate(text: String, fromClipboard: Boolean): Boolean =
        fromClipboard && text.trim().length > PASTE_MIN_CHARS

    // ---------- Normalização de phráse natural → pedido canônico ----------

    private val REWRITES: List<Pair<Regex, String>> = listOf(
        Regex("como\\s+(posso\\s+)?(comecar|comeco|iniciar|abrir)\\b.*") to "Crie uma introdução",
        Regex("como\\s+(posso\\s+)?(concluir|terminar|fechar|encerrar|finalizar)\\b.*") to "Faça uma conclusão",
        Regex("como\\s+(posso\\s+)?(passo|passar|ligo|ligar|conecto|conectar|vou|ir)\\b.*(proximo|proxima|seguinte|outro ponto).*") to "Crie uma transição",
        Regex("(me\\s+)?ajud[ae]\\s+a\\s+desenvolver\\b.*") to "Desenvolva este ponto",
        Regex("(pode|poderia)\\s+(explicar|elaborar|detalhar|aprofundar)\\s+(melhor\\s+)?(o\\s+)?ponto\\s*(\\d{1,2}).*") to "Desenvolva o ponto $1",
        Regex("quero\\s+desenvolver\\s+(o\\s+)?ponto\\s*(\\d{1,2}).*") to "Desenvolva o ponto $1",
        Regex("como\\s+(eu\\s+)?desenvolvo\\s+(o\\s+)?(esse|este|isso|ponto).*") to "Desenvolva este ponto",
        Regex("como\\s+(posso\\s+)?(comecar|iniciar)\\s+(esse|este)\\s+(discurso|tema).*") to "Crie uma introdução"
    )

    /** Reescreve phráse natural para o vocabulário canônico; null = sem reescrita. */
    fun normalizePhrasing(text: String): String? {
        val t = " ${normalizeText(text)} "
        for ((re, canonical) in REWRITES) {
            val m = re.find(t)
            if (m != null) {
                // Varre TODOS os grupos: o índice do número varia por regex.
                val n = m.groupValues.drop(1)
                    .mapNotNull { it.trim().toIntOrNull() }
                    .firstOrNull { it in 1..99 }
                return if (n != null && canonical.contains("ponto")) {
                    canonical.replace("$1", "$n")
                } else canonical
            }
        }
        return null
    }

    // ---------- T3 (Mini Discurso): inserção em linguagem natural ----------

    /** Verbos de inserção (texto normalizado, sem acento; "coloque"→coloqu). */
    private val INSERT_VERB = Regex(
        "\\b(insir|inser|coloc|coloqu|adicion|inclu)\\w*\\b|\\b(poe|ponha)\\b"
    )

    /**
     * Referência ao texto (anáfora) — #8c (GAP-9): aceita paráfrases
     * ("isso aí", "o que você escreveu", "esse conteúdo"). "o texto" puro
     * ficou de fora: "coloca o texto" não é pedido de inserir o que existe.
     */
    private val INSERT_ANAPHORA = Regex(
        "\\b(isso|isto|essa resposta|esse texto|o que voce escreveu|o que escreveu|" +
            "esse conteudo|isso ai|esse trecho|essa parte|essa sugestao|essa ideia|" +
            "essa mensagem|essa proposta)\\b"
    )

    /** Destino explícito e genérico (discurso/tópico/nota), sem número. */
    private val INSERT_DESTINATION = Regex(
        "\\b(no|ao|na|nas|nos|para o|pro)\\s+(mini discurso|mini|discurso|topico|texto|esboco|nota)\\b"
    )

    /** Objeto de criação NOVA: "insira uma ilustração…" não é inserir o que existe. */
    private val CREATION_OBJECT = Regex(
        "\\b(um|uma)\\s+(ilustracao|exemplo|pergunta|transicao|introducao|conclusao|aplicacao|" +
            "historia|versiculo|paragrafo|frase|sugestao|ideia|piada|analogia|dado|estatistica|" +
            "citacao|comentario|observacao|texto|trecho|titulo|topico|ponto)\\b"
    )

    /** Alvo numerado ("no ponto 2"): fora do escopo desta rodada — não sequestra. */
    private val NUMBERED_TARGET = Regex("\\b(ponto|topico|secao)\\s+\\d{1,2}\\b")

    /** #8c (GAP-9): hedge no início da oração bloqueia o comando. */
    private val HEDGE_START = Regex("^(acho que|acho|talvez|sera que|quem sabe|de repente)\\b")

    /** Orações separadas por vírgula/ponto e vírgula, normalizadas. */
    private fun insertClauses(text: String): List<String> =
        text.split(Regex("[,;]")).map { normalizeText(it) }.filter { it.isNotBlank() }

    /**
     * #8c (GAP-9) — pedido de inserir "isso" no card, aceitando paráfrases:
     * verbo de ação + referência ao texto, sem hedge. Oração por oração
     * ("acho que fechamos, vamos colocar isso no card" → SIM; "acho que devia
     * colocar isso no card" → NÃO). Puro/testável.
     */
    fun isInsertCommand(text: String): Boolean = insertClauses(text).any { c ->
        !HEDGE_START.containsMatchIn(c) &&
            INSERT_VERB.containsMatchIn(c) &&
            INSERT_ANAPHORA.containsMatchIn(c)
    }

    /**
     * T3 — comando de inserção no mini discurso/tópico em linguagem natural:
     * "insira isso no mini discurso", "insira no tópico", "coloca isso no
     * card", "vamos colocar isso no card". Determinístico (nunca vai ao LLM).
     *
     * Exclui: criação nova ("insira uma ilustração no ponto 2"), alvo
     * numerado ("insira isso no ponto 2"), esqueleto e cards ("insira a 2").
     * #8c: a referência agora vem de [isInsertCommand] (paráfrases + hedge).
     */
    fun isInsertIntoSpeechCommand(text: String): Boolean {
        val t = " ${normalizeText(text)} "
        if (t.contains("esqueleto")) return false
        if (!INSERT_VERB.containsMatchIn(t)) return false
        if (NUMBERED_TARGET.containsMatchIn(t)) return false
        if (isInsertCommand(text)) return true
        // Destino explícito sem referência ("insira no tópico") — legado.
        return insertClauses(text).any { c ->
            !HEDGE_START.containsMatchIn(c) &&
                INSERT_DESTINATION.containsMatchIn(" $c ") &&
                !CREATION_OBJECT.containsMatchIn(" $c ")
        }
    }

    // ---------- F5b (Modo C): estruturar texto colado de publicação ----------

    /** Verbos inequívocos de estruturação (texto normalizado). */
    private val STRUCT_PASTED_VERB = Regex("\\b(estruturar|transforma|transformar|organiza|organizar|vira|virar)\\b")

    /**
     * "estrutura" é verbo E substantivo (ambíguo): só vale como comando
     * quando seguido de alvo claro ("estrutura como esboço", "estrutura isso
     * em seções") — "estrutura do discurso está boa" NÃO é comando.
     */
    private val STRUCT_PASTED_IMPERATIVE = Regex("\\bestrutura\\s+(como|em|isso|isto|o texto|esse|essa|este|esta)\\b")

    /** Substantivo forte do comando (o que a estruturação produz). */
    private val STRUCT_PASTED_NOUN = Regex("\\b(esboco|esbocos|discurso|secoes|secao)\\b")

    /**
     * F5b (Modo C) — comando de estruturar o texto colado: "estruturar como
     * esboço", "estrutura como esboço", "transforma em esboço", "organiza em
     * seções", "organizar como discurso", "isto vira um discurso".
     *
     * Verbo obrigatório + substantivo forte. Perguntas abertas ("qual a
     * estrutura do discurso?") NÃO sequestram. Puro/testável.
     */
    fun isStructureFromPastedCommand(text: String): Boolean {
        val t = normalizeText(text).trim()
        if (t.isEmpty()) return false
        if (QUESTION_OPENERS.containsMatchIn(t)) return false
        val padded = " $t "
        if (!STRUCT_PASTED_VERB.containsMatchIn(padded) &&
            !STRUCT_PASTED_IMPERATIVE.containsMatchIn(padded)
        ) {
            return false
        }
        return STRUCT_PASTED_NOUN.containsMatchIn(padded)
    }

    // ---------- F5d (Modo B): ângulos para um discurso do zero ----------

    /** Substantivo forte do pedido de opções. */
    private val ANGLES_NOUN = Regex("\\b(opcoes|angulos|abordagens|alternativas|possibilidades)\\b")

    /** "como (posso|poderia) falar sobre X" — pedido direto de abordagem. */
    private val ANGLES_COMO_FALAR = Regex("\\bcomo\\s+(posso\\s+|poderia\\s+)?falar\\b")

    /** Indícios de PEDIDO (não pergunta aberta): me dá, sugere, quero, posso… */
    private val ANGLES_REQUEST = Regex(
        "\\b(me\\s+d[ae]s?|sugere|sugerir|sugira|mostra|mostrar|mostre|quero|posso|poderia|" +
            "indica|indicar|indique|apresenta|apresentar|traz|traga)\\b"
    )

    /**
     * F5d: perguntas explícitas que NÃO pedem opções ("o que você acha de X?",
     * "qual a melhor opção…"). "que ângulos posso usar…" NÃO cai aqui (o
     * "que" nu não abre pergunta quando há pedido de opções).
     */
    private val ANGLES_QUESTION_GUARD = Regex(
        "^(o que|qual|quais|quanto|quantos|quantas|existe|existem|onde|quem|quando)\\b"
    )

    /**
     * F5d (Modo B) — comando de opções/ângulos: "me dá opções para (um
     * discurso sobre) X", "sugere ângulos para X", "3 abordagens (possíveis)
     * para X", "que ângulos posso usar para X", "como posso falar sobre X".
     *
     * Verbo/moldura obrigatória + substantivo forte (ou "como falar").
     * Perguntas abertas não sequestram. Puro/testável.
     */
    fun isAnglesForThemeCommand(text: String): Boolean {
        val t = normalizeText(text).trim()
        if (t.isEmpty()) return false
        if (ANGLES_QUESTION_GUARD.containsMatchIn(t)) return false
        val padded = " $t "
        if (ANGLES_COMO_FALAR.containsMatchIn(padded)) return true
        if (!ANGLES_NOUN.containsMatchIn(padded)) return false
        return ANGLES_REQUEST.containsMatchIn(padded) || padded.contains(" para ")
    }

    // ---------- #8f: Necessidades Locais (discurso curto do zero) ----------

    /** #8f: dados extraídos do pedido de NL. Puro/testável. */
    data class LocalNeedsInputs(
        val theme: String,
        val minutes: Int,
        val audience: Audience,
    )

    /** Moldura de NL: "necessidades locais", "NL", "discurso curto", "discurso de N min". */
    private val NL_FRAME = Regex("\\b(necessidades\\s+locais|nl)\\b")
    private val NL_SHORT_SPEECH = Regex("\\bdiscurso\\s+(curto|de\\s+\\d{1,2}\\s*min)")
    /** Não sequestra ângulos ("3 ângulos para X") — Modo B tem precedência própria. */
    private val NL_ANGLES_GUARD = Regex("\\b(opcoes|angulos|abordagens|alternativas|possibilidades)\\b")

    fun isLocalNeedsCommand(text: String): Boolean = extractLocalNeedsInputs(text) != null

    /**
     * #8f: extrai tema/tempo/público do pedido de NL. Exige moldura explícita
     * ("discurso curto", "necessidades locais", "NL", "discurso de N min") e
     * tema após "sobre/a respeito de" — nunca sequestra oratória nem ângulos.
     * Puro/testável.
     */
    fun extractLocalNeedsInputs(text: String): LocalNeedsInputs? {
        val t = " ${normalizeText(text).trim()} "
        if (!NL_FRAME.containsMatchIn(t) && !NL_SHORT_SPEECH.containsMatchIn(t)) return null
        if (NL_ANGLES_GUARD.containsMatchIn(t)) return null
        val match = Regex("(?i)\\b(?:sobre|a\\s+respeito\\s+de)\\s+(.+)").find(text.trim())
            ?: return null
        val theme = match.groupValues[1].trim()
            .trimEnd('.', '!', '?', ',', ';', ':')
            .trim()
        if (theme.isBlank()) return null
        val minutes = Regex("\\b(\\d{1,2})\\s*min").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 5
        val audience = when {
            Regex("\\b(novos|novas|estudantes)\\b").containsMatchIn(t) -> Audience.NEW
            Regex("\\b(jovens?)\\b").containsMatchIn(t) -> Audience.YOUNG
            else -> Audience.GENERAL
        }
        return LocalNeedsInputs(theme, minutes.coerceIn(3, 15), audience)
    }

    // ---------- Réplica de proposta ----------

    private val ACCEPT_RE = Regex("^(aceitar|aceito|aplicar|aplique|confirmar|confirmo)\\b")
    private val REJECT_RE = Regex("^(rejeitar|rejeito|descartar|descarte|dispensar|recusar)\\b")

    // ---------- T2 (Fase 2): materializar a estrutura ("insira tudo") ----------

    private val APPLY_VERB = Regex("\\b(insira|insere|inserir|coloque|colocar|materialize|materializar|" +
        "transcreva|transcrever|aplique|aplicar|crie|criar)\\b")
    private val APPLY_ALL = Regex("\\btudo\\b")
    /** #16-fix-a: alvo explícito de materialização ("na nota", "no editor"). */
    private val APPLY_TARGET = Regex("\\b(na|para a|no)\\s+(nota|editor)\\b")
    /** #16-fix-a: verbo de transcrição/materialização explícito. */
    private val APPLY_MATERIALIZE = Regex("\\b(materialize|materializar|transcreva|transcrever)\\b")
    /** #16-fix-a: objeto "estrutura/seções" — materialização legítima. */
    private val APPLY_STRUCTURE_NOUN = Regex("\\b(estrutura|estruturas|secao|secoes)\\b")
    /**
     * #16-fix-a: geração de SEÇÃO (introdução/conclusão) — o objeto do verbo é
     * criar texto, não materializar a estrutura. Sem marcador forte
     * ("tudo"/alvo), NÃO é comando de materialização (bug do teste #16: "Crie
     * uma introdução para este discurso" era sequestrado pelo apply-structure
     * e o parse da estrutura falhava).
     */
    private val GENERATION_SECTION = Regex("\\b(introducao|conclusao)\\b")

    /**
     * T2 — comando de materialização: "insira tudo na nota", "materialize o
     * discurso", "crie as seções". Exclui o "mini" (T3 mini é dono dele).
     *
     * #16-fix-a: exige intenção EXPLÍCITA de materialização — marcador
     * ("tudo"), alvo ("na nota"/"no editor"), verbo de materialização ou o
     * objeto "estrutura/seções". "Crie uma introdução para este discurso" cai
     * no chat/oratória normal. Em dúvida, o padrão é `false`. Puro/testável.
     */
    fun isApplyStructureCommand(text: String): Boolean {
        val t = " ${normalizeText(text)} "
        if (t.contains("mini")) return false
        // "crie um ponto 4" é OutOfScope (§34), não materialização.
        if (NUMBERED_TARGET.containsMatchIn(t)) return false
        if (!APPLY_VERB.containsMatchIn(t)) return false
        val strong = APPLY_ALL.containsMatchIn(t) || APPLY_TARGET.containsMatchIn(t)
        if (GENERATION_SECTION.containsMatchIn(t) && !strong) return false
        return strong ||
            APPLY_MATERIALIZE.containsMatchIn(t) ||
            APPLY_STRUCTURE_NOUN.containsMatchIn(t)
    }

    private val PROPOSE_STRUCT_VERB = Regex("\\b(propoe|propor|propoem|sugere|sugerir|sugira|" +
        "planeja|planejar|planeje|define|defina|definir|usa|usar|use)\\b")
    private val PROPOSE_STRUCT_PLAN_VERB = Regex("\\b(planeja|planejar|planeje)\\b")
    private val PROPOSE_STRUCT_NOUN_STRICT = Regex("\\b(estrutura|plano|objetivo|progressao)\\b")
    private val PROPOSE_STRUCT_TEXTS = Regex("\\btextos\\b")
    private val PROPOSE_STRUCT_SCOPE = Regex("\\b(secao|secoes|topico|topicos|ponto|pontos|parte)\\b")

    /**
     * T2 (Fase 2.1) — ordem dos intercepts do `send()`: colagem NOVA primeiro;
     * o comando explícito de materializar ("insira tudo na nota") vence o
     * fallback de conversa sobre o esboço colado — antes, o ramo do
     * `pasteBuffer` o sequestrava (validado no A34). Puro/testável.
     */
    enum class Intercept { NEW_PASTE, APPLY_STRUCTURE, PASTE_CHAT, NONE }

    fun interceptFor(
        text: String,
        canStartPaste: Boolean,
        pasteActive: Boolean,
        fromClipboard: Boolean = false,
    ): Intercept = when {
        canStartPaste && isPasteCandidate(text, fromClipboard) -> Intercept.NEW_PASTE
        isApplyStructureCommand(text) -> Intercept.APPLY_STRUCTURE
        pasteActive -> Intercept.PASTE_CHAT
        else -> Intercept.NONE
    }

    /**
     * FALTA-2 — comando de estrutura da seção: "propõe estrutura (para esta
     * seção)", "propõe o plano", "planeja a seção", "define o objetivo",
     * "que textos usar nesta seção".
     *
     * Regras estritas (verbo obrigatório):
     * - verbo + substantivo forte (estrutura/plano/objetivo/progressão) → sim;
     * - verbo de planejamento (planeja/planejar) + escopo (seção/tópico) → sim;
     * - "textos" (plural) + escopo (ou foco) → sim — "como usar este texto"
     *   (singular, sem escopo) NÃO sequestra; "use esta seção como
     *   introdução" não tem substantivo/planejamento → NÃO.
     * Puro/testável.
     */
    fun isProposeStructureCommand(text: String, focused: Boolean = false): Boolean {
        val t = " ${normalizeText(text)} "
        if (!PROPOSE_STRUCT_VERB.containsMatchIn(t)) return false
        if (PROPOSE_STRUCT_NOUN_STRICT.containsMatchIn(t)) return true
        val scope = PROPOSE_STRUCT_SCOPE.containsMatchIn(t)
        if (PROPOSE_STRUCT_PLAN_VERB.containsMatchIn(t) && scope) return true
        if (PROPOSE_STRUCT_TEXTS.containsMatchIn(t) && (scope || focused)) return true
        return false
    }

    // ---------- Pergunta estrutural ----------

    private val QUESTION_OPENERS = Regex(
        "^(qual|quais|quanto|quantos|quantas|o que|que|existe|existem|tem|ha|mostre|mostrar|liste|listar|" +
            "resuma|resumir|onde|quem|quando)\\b"
    )
    /** Palavras de meta-linguagem: falam SOBRE o termo, não pedem geração. */
    private val META_WORDS = Regex("\\b(termo|palavra|significa|significado|quer dizer)\\b")

    /**
     * Intenção de explicação/análise: conversa, não geração de texto de card.
     * Um verbo explícito de escrita ("escreva", "redija", "monte", "desenvolva")
     * vence — aí sim é oratória.
     */
    private val EXPLANATION_INTENT = Regex(
        "\\b(explique|explicar|entenda|entender|analise|analisar|compreenda|compreender)\\b"
    )
    private val WRITING_INTENT = Regex(
        "\\b(escreva|escrever|redija|redigir|monte|montar|desenvolva|desenvolver|" +
            "crie|criar|elabore|elaborar|produza|produzir)\\b"
    )
    /** "explique o ponto 2" continua sendo iteração de oratória. */
    private val SECTION_CONTEXT = Regex(
        "\\b(ponto|pontos|secao|secoes|discurso|discursos|texto|textos|bloco|blocos|" +
            "introducao|conclusao|transicao)\\b"
    )
    /** Follow-up curto de uma geração já existente ("explique melhor"). */
    private val ITERATION_HINT = Regex(
        "\\b(melhor|mais|continue|continuar|detalhe|detalhar|aprofunde|aprofundar|" +
            "refaca|refazer|outra|outro)\\b"
    )

    /**
     * C6 (Sessão 2): pergunta de orientação — "Como/Qual/O que…" sem verbo
     * imperativo de geração. Sem contexto de seção, é conversa/mentoria, não
     * refinamento oratório. "Como faço a entonação ficar melhor?" caía em
     * NothingToRefine porque "melhor" casava [OratorySession.isRefinement].
     * "Como começo esse discurso?" continua na oratória (SECTION_CONTEXT).
     */
    private val ORIENTATION_QUESTION_START = Regex("^\\s*(como|qual|quais|o que|que)\\b")
    private val EXPLICIT_GENERATION_VERB = Regex(
        "\\b(crie|criar|gere|gerar|escreva|escrever|redija|redigir|monte|montar|" +
            "elabore|elaborar|produza|produzir|desenvolva|desenvolver|faca|fazer|" +
            "refaca|refazer|reescreva|reescrever|ajuste|ajustar|corrija|corrigir)\\b"
    )

    /** Puro/testável: explicação geral, sem escrita nem foco de seção. */
    fun isExplanationOnly(text: String): Boolean {
        val n = " ${normalizeText(text)} "
        return EXPLANATION_INTENT.containsMatchIn(n) &&
            !WRITING_INTENT.containsMatchIn(n) &&
            !SECTION_CONTEXT.containsMatchIn(n)
    }

    // ---------- Texto longo: total vs. seção ----------

    /** Pedido do texto TOTAL do discurso/demonstração. */
    private val TOTAL_TEXT_CUE = Regex(
        "\\b(texto total|texto completo|discurso inteiro|discurso todo|" +
            "a parte inteira|todo o discurso|todo o texto|todas as secoes|" +
            "todos os topicos|a nota inteira|o roteiro inteiro)\\b"
    )
    private val TOTAL_TEXT_REQUEST = Regex(
        "\\b(gere|gerar|escreva|escrever|redija|redigir|monte|montar|crie|criar|" +
            "faca|fazer|desenvolva|desenvolver|produza|produzir|quero|preciso|" +
            "me de|manda|envie)\\b"
    )

    /**
     * Pedido de TEXTO TOTAL ("escreva o texto completo", "gere o discurso
     * inteiro"). O chat não tenta um único texto gigante: divide por seção e
     * encaminha para o DraftSheet. Perguntas abertas ("qual o texto total?")
     * não sequestram. Puro/testável.
     */
    fun isTotalTextCommand(text: String): Boolean {
        val t = normalizeText(text).trim()
        if (t.isEmpty()) return false
        val padded = " $t "
        if (!TOTAL_TEXT_CUE.containsMatchIn(padded)) return false
        val requests = TOTAL_TEXT_REQUEST.containsMatchIn(padded)
        if (!requests && (text.trim().endsWith("?") || QUESTION_OPENERS.containsMatchIn(t))) {
            return false
        }
        return requests || t.length <= 40
    }

    /** Objeto de escrita: texto/discurso/diálogo/seção — não "uma ilustração". */
    private val LONG_TEXT_OBJECT = Regex(
        "\\b(texto|discurso|dialogo|fala|secao|secoes|topico|topicos|ponto|pontos|" +
            "introducao|conclusao|desenvolvimento|demonstracao|roteiro|bloco)\\b"
    )
    private val LONG_TEXT_WRITE = Regex(
        "\\b(escreva|escreve|escrever|gere|gera|gerar|redija|redige|redigir|" +
            "monte|monta|montar|desenvolva|desenvolve|desenvolver|crie|cria|criar|" +
            "produza|produz|produzir|faca|faz|fazer|refaca|refaz|refazer|" +
            "reescreva|reescreve|reescrever|quero|preciso|me de|manda|envie)\\b"
    )

    /**
     * A mensagem pede para ESCREVER um texto/diálogo (não só explicar)? Usado
     * para aplicar o orçamento de texto longo com o tempo da seção.
     * Puro/testável.
     */
    fun isLongTextGenerationRequest(text: String): Boolean {
        val t = " ${normalizeText(text)} "
        if (isExplanationOnly(text)) return false
        return LONG_TEXT_WRITE.containsMatchIn(t) && LONG_TEXT_OBJECT.containsMatchIn(t)
    }

    private val TOPIC_OBJECTIVE = Regex("\\b(objetivo|proposito|meta|tema)\\b")
    private val TOPIC_POINTS = Regex("\\b(pontos principais|pontos|topicos|secoes|estrutura)\\b")
    private val TOPIC_SEQUENCE = Regex("\\b(sequencia|ordem|primeiro|ultimo|antes|depois)\\b")
    private val TOPIC_REFERENCES =
        Regex("\\b(referencias|referencia|textos|versiculos|publicacoes|publicacao|citac|w\\d{2})\\b")

    fun route(
        text: String,
        document: S34Document?,
        currentSectionId: String?,
        last: OratorySession.LastGeneration?
    ): Route {
        val t = " ${normalizeText(text)} "

        // 1) Réplica de proposta: fluxo existente, nunca o LLM (§§27-28).
        //    Apenas formas inequívocas; "sim"/"não" continuam nos fluxos atuais.
        when {
            ACCEPT_RE.containsMatchIn(t.trim()) ->
                return Route.ProposalReply(true, "resposta de aceite")
            REJECT_RE.containsMatchIn(t.trim()) ->
                return Route.ProposalReply(false, "resposta de rejeição")
        }

        // T2 (Fase 2): materializar a estrutura — antes da oratória e do
        // meta, para "crie as seções" não virar geração e "insira tudo na
        // nota" não cair no geral.
        if (isApplyStructureCommand(text)) {
            return Route.ApplyStructure("pedido de materializar a estrutura na nota")
        }

        // F5b (Modo C): estruturar texto colado numa seção — antes da
        // proposta de seção (FALTA-2), que sequestraria "estrutura".
        if (isStructureFromPastedCommand(text)) {
            return Route.StructureFromPasted("pedido de estruturar o texto colado")
        }

        // #8f: Necessidades Locais (discurso curto do zero) — antes dos ângulos
        // (que sequestrariam "discurso … para") e da oratória (verbo "cria").
        extractLocalNeedsInputs(text)?.let {
            return Route.GenerateLocalNeeds(it, "pedido de discurso curto (Necessidades Locais)")
        }

        // F5d (Modo B): opções/ângulos para um tema do zero — antes da
        // proposta de seção (que sequestraria "sugere … para").
        if (isAnglesForThemeCommand(text)) {
            return Route.AnglesForTheme(
                theme = ChatIntent.extractThemeForAngles(text),
                reason = "pedido de opções/ângulos para o tema",
            )
        }

        // FALTA-2: propor a estrutura da seção em foco — antes da oratória
        // (que sequestraria verbos como "crie") e do meta. Exige verbo +
        // substantivo estritos, mais palavra de escopo ou foco atual.
        if (isProposeStructureCommand(text, focused = currentSectionId != null)) {
            return Route.ProposeStructure("pedido de estrutura da seção em foco")
        }

        // Meta-linguagem ("o termo desenvolvimento", "o significado de X"):
        // o usuário fala SOBRE a palavra, não pede geração (§41).
        if (META_WORDS.containsMatchIn(t)) return Route.General("meta-linguagem sobre um termo")

        // Correção 1: explicar/analisar é conversa, não proposta de texto.
        // Exceção: follow-up curto de uma geração já existente ("explique
        // melhor") continua a iteração oratória. Uma explicação nova vence
        // o estado `last`, mesmo depois de uma geração anterior.
        val shortIteration = last != null && ITERATION_HINT.containsMatchIn(t)
        if (isExplanationOnly(text) && !shortIteration) {
            return Route.General("pedido de explicação/análise")
        }

        // 2)/3) Oratória: pedido explícito, phráse natural ou iteração.
        val canonical = normalizePhrasing(text)
        // C6 (Sessão 2) + resíduo: pergunta de orientação NÃO é refinamento
        // oratório, mesmo quando cita uma seção ("Como melhorar a entonação
        // do ponto 1?"). Só quando NÃO há reescrita canônica ("Como posso
        // concluir?" → "Faça uma conclusão" continua sendo geração).
        if (canonical == null &&
            ORIENTATION_QUESTION_START.containsMatchIn(t) &&
            !EXPLICIT_GENERATION_VERB.containsMatchIn(t) &&
            // Consulta estrutural do S-34 continua determinística.
            (document == null || structuralTopic(t) == null)
        ) {
            return Route.General("pergunta de orientação, não geração")
        }
        val decision = OratorySession.decide(canonical ?: text, document, currentSectionId, last)
        when (decision) {
            is OratorySession.Decision.Generate -> return Route.Oratory(
                mode = decision.mode,
                sectionId = decision.sectionId,
                action = decision.action,
                inherited = decision.inherited,
                reason = if (decision.inherited) "iteração da sessão oratória" else "pedido oratório explícito"
            )
            is OratorySession.Decision.OutOfStructuralScope -> return Route.OutOfScope(
                decision.requestedPoint, decision.message, "pedido de estrutura fora do S-34"
            )
            is OratorySession.Decision.NothingToRefine -> {
                // Refinamento sem geração anterior: sinal explícito, para o
                // chat responder de forma amigável em vez de gerar algo.
                if (OratorySession.isRefinement(text)) {
                    return Route.NothingToRefine(
                        OratorySession.NOTHING_TO_REFINE_MESSAGE,
                        "refinamento sem sessão oratória"
                    )
                }
            }
        }

        // 4) Pergunta estrutural sobre o S-34 (sem proposta) (§18).
        if (document != null) {
            val topic = structuralTopic(t)
            if (topic != null) {
                return Route.StructuralQuery(
                    topic = topic,
                    sectionNumber = OratorySession.requestedPoint(text),
                    reason = "pergunta sobre a estrutura do S-34"
                )
            }
        }

        // 5) Chat geral (§26).
        return Route.General("chat livre")
    }

    private fun structuralTopic(t: String): StructuralTopic? {
        // Meta-linguagem ("explique o termo desenvolvimento") não é consulta
        // estrutural nem geração (§41).
        if (META_WORDS.containsMatchIn(t)) return null
        val isQuestion = t.trim().endsWith("?") || QUESTION_OPENERS.containsMatchIn(t.trim())
        if (!isQuestion) return null
        return when {
            TOPIC_REFERENCES.containsMatchIn(t) -> StructuralTopic.REFERENCES
            TOPIC_OBJECTIVE.containsMatchIn(t) -> StructuralTopic.OBJECTIVE
            // Sequência antes de "pontos": "sequência dos pontos" é ordem.
            TOPIC_SEQUENCE.containsMatchIn(t) -> StructuralTopic.SEQUENCE
            TOPIC_POINTS.containsMatchIn(t) -> StructuralTopic.POINTS
            Regex("\\bs\\s*34\\b").containsMatchIn(t) -> StructuralTopic.ABOUT
            else -> null
        }
    }

    /** Diagnóstico para teste/log: nunca inclui texto do documento nem chave. */
    fun describe(route: Route): String = when (route) {
        is Route.Oratory -> "route=ORATORY mode=${route.mode} section=${route.sectionId ?: "—"} " +
            "action=${route.action} inherited=${route.inherited} reason=${route.reason}"
        is Route.StructuralQuery -> "route=STRUCTURAL_QUERY topic=${route.topic} " +
            "section=${route.sectionNumber ?: "—"} reason=${route.reason}"
        is Route.ProposalReply -> "route=PROPOSAL_REPLY accept=${route.accept} reason=${route.reason}"
        is Route.NothingToRefine -> "route=NOTHING_TO_REFINE reason=${route.reason}"
        is Route.OutOfScope -> "route=OUT_OF_SCOPE point=${route.requestedPoint} reason=${route.reason}"
        is Route.ApplyStructure -> "route=APPLY_STRUCTURE reason=${route.reason}"
        is Route.ProposeStructure -> "route=PROPOSE_STRUCTURE reason=${route.reason}"
        is Route.StructureFromPasted -> "route=STRUCTURE_FROM_PASTED reason=${route.reason}"
        is Route.AnglesForTheme -> "route=ANGLES_FOR_THEME theme=${route.theme ?: "—"} reason=${route.reason}"
        is Route.GenerateLocalNeeds -> "route=GENERATE_LOCAL_NEEDS theme=${route.inputs.theme} " +
            "minutes=${route.inputs.minutes} reason=${route.reason}"
        is Route.PasteAndChat -> "route=PASTE_AND_CHAT reason=${route.reason}"
        is Route.General -> "route=GENERAL reason=${route.reason}"
    }
}
