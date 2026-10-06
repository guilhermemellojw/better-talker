package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.util.normalizeText

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

        data class General(val reason: String) : Route
    }

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
    private val INSERT_VERB = Regex("\\b(insir|inser|coloc|coloqu|adicion|inclu)\\w*\\b")

    /** Referência ao que já foi dito (a resposta anterior do Copilot). */
    private val INSERT_ANAPHORA = Regex(
        "\\b(isso|isto|esse texto|essa resposta|essa sugestao|esse trecho|essa parte|" +
            "essa ideia|esse conteudo|essa mensagem|o texto|essa proposta)\\b"
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

    /**
     * T3 — comando de inserção no mini discurso/tópico em linguagem natural:
     * "insira isso no mini discurso", "insira no tópico", "coloque no
     * discurso", "adicione ao mini discurso", "insira esse texto".
     * Determinístico (nunca vai ao LLM). Puro/testável.
     *
     * Exclui: criação nova ("insira uma ilustração no ponto 2"), alvo
     * numerado ("insira isso no ponto 2"), esqueleto e cards ("insira a 2").
     */
    fun isInsertIntoSpeechCommand(text: String): Boolean {
        val t = " ${normalizeText(text)} "
        if (t.contains("esqueleto")) return false
        if (!INSERT_VERB.containsMatchIn(t)) return false
        if (NUMBERED_TARGET.containsMatchIn(t)) return false
        val anaphora = INSERT_ANAPHORA.containsMatchIn(t)
        if (!anaphora && CREATION_OBJECT.containsMatchIn(t)) return false
        return anaphora || INSERT_DESTINATION.containsMatchIn(t)
    }

    // ---------- Réplica de proposta ----------

    private val ACCEPT_RE = Regex("^(aceitar|aceito|aplicar|aplique|confirmar|confirmo)\\b")
    private val REJECT_RE = Regex("^(rejeitar|rejeito|descartar|descarte|dispensar|recusar)\\b")

    // ---------- Pergunta estrutural ----------

    private val QUESTION_OPENERS = Regex(
        "^(qual|quais|quanto|quantos|quantas|o que|que|existe|existem|tem|ha|mostre|mostrar|liste|listar|" +
            "resuma|resumir|onde|quem|quando)\\b"
    )
    /** Palavras de meta-linguagem: falam SOBRE o termo, não pedem geração. */
    private val META_WORDS = Regex("\\b(termo|palavra|significa|significado|quer dizer)\\b")

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

        // Meta-linguagem ("o termo desenvolvimento", "o significado de X"):
        // o usuário fala SOBRE a palavra, não pede geração (§41).
        if (META_WORDS.containsMatchIn(t)) return Route.General("meta-linguagem sobre um termo")

        // 2)/3) Oratória: pedido explícito, phráse natural ou iteração.
        val canonical = normalizePhrasing(text)
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
        is Route.General -> "route=GENERAL reason=${route.reason}"
    }
}
