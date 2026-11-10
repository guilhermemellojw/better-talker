package com.bettertalker.app.domain.help

import com.bettertalker.app.navigation.Routes
import java.text.Normalizer
import java.util.Locale

/**
 * Catálogo local da ajuda do 5.0. Não depende de rede, chave, banco ou
 * conteúdo da nota. As ações são resolvidas pela camada de navegação.
 */
object HelpCatalog {

    val topics: List<HelpTopic> = listOf(
        HelpTopic(
            id = "primeira-nota",
            question = "Como começo minha primeira nota?",
            shortAnswer = "Escolha um dos quatro caminhos do BT: importar um " +
                "esboço S-34, colar uma parte V&M, criar do zero ou explorar.",
            steps = listOf(
                "Toque em “Começar” na abertura guiada.",
                "Escolha importar, colar, criar ou explorar.",
                "Ao terminar, você cai direto no editor ou nos Esboços.",
            ),
            action = HelpAction.OPEN_HELP_TOPIC,
            keywords = listOf("começar", "início", "primeira", "nota", "onboarding", "caminhos"),
        ),
        HelpTopic(
            id = "importar-s34",
            question = "Como importo um esboço S-34?",
            shortAnswer = "Use o arquivo JWPUB ou DOCX do esboço (PDF como fallback). O BT " +
                "extrai seções, tempos e referências automaticamente.",
            steps = listOf(
                "Abra Esboços e toque no botão de criar.",
                "Escolha “Importar esboço S-34”.",
                "Selecione o arquivo e confira as seções no editor.",
            ),
            action = HelpAction.OPEN_IMPORT,
            keywords = listOf("importar", "s-34", "docx", "pdf", "jwpub", "esboço", "arquivo"),
            routes = setOf(Routes.HOME, Routes.ASSISTANT),
        ),
        HelpTopic(
            id = "colar-vm",
            question = "Como colar uma parte da reunião V&M?",
            shortAnswer = "Copie o texto da parte e cole no BT. Ele reconhece " +
                "a estrutura (Introdução, pontos e Conclusão) e cria os blocos.",
            steps = listOf(
                "Abra Esboços e toque em criar.",
                "Escolha “Colar parte da reunião V&M”.",
                "Confira a prévia e aplique na nota.",
            ),
            action = HelpAction.OPEN_PASTE,
            keywords = listOf("colar", "vida", "ministério", "v&m", "parte", "reunião", "texto"),
            routes = setOf(Routes.HOME, Routes.ASSISTANT),
        ),
        HelpTopic(
            id = "criar-do-zero",
            question = "Como criar um esboço do zero?",
            shortAnswer = "O BT monta a estrutura com Introdução, pontos e " +
                "Conclusão a partir do tipo e da duração que você escolher.",
            steps = listOf(
                "Abra Esboços e toque em criar.",
                "Escolha “Criar esboço do zero”.",
                "Informe título, tipo, duração e pontos.",
            ),
            action = HelpAction.OPEN_CREATE,
            keywords = listOf("criar", "zero", "novo", "esboço", "estrutura", "duração"),
            routes = setOf(Routes.HOME, Routes.ASSISTANT),
        ),
        HelpTopic(
            id = "copilot",
            question = "O que o Copilot faz?",
            shortAnswer = "O Copilot conversa sobre o discurso, sugere melhorias " +
                "e pode gerar texto por seção. Sem chave, ele continua com ajuda local.",
            steps = listOf(
                "Toque no ✨ de uma seção ou abra o Assistente.",
                "Faça uma pergunta ou peça uma sugestão.",
                "Revise a proposta antes de aplicar.",
            ),
            action = HelpAction.OPEN_ASSISTANT,
            keywords = listOf("copilot", "assistente", "ia", "chat", "sugestão", "gerar"),
            routes = setOf(Routes.ASSISTANT, Routes.HOME, Routes.EDITOR),
        ),
        HelpTopic(
            id = "chave-ia",
            question = "Preciso de chave de IA?",
            shortAnswer = "Só para geração de texto com o DeepSeek. O editor, " +
                "a ajuda, o mapa mental manual e o Treino funcionam sem chave.",
            steps = listOf(
                "Abra Modelo IA.",
                "Cole sua chave pessoal do DeepSeek.",
                "A chave fica cifrada no aparelho e nunca vai para backup.",
            ),
            action = HelpAction.OPEN_MODEL,
            keywords = listOf("chave", "ia", "deepseek", "configurar", "api", "modelo"),
            routes = setOf(Routes.ASSISTANT, Routes.HOME, Routes.EDITOR),
        ),
        HelpTopic(
            id = "fontes",
            question = "Como funcionam as fontes e a Biblioteca?",
            shortAnswer = "O BT usa as publicações indexadas no aparelho para " +
                "mostrar o texto das referências citadas no esboço.",
            steps = listOf(
                "Abra Biblioteca para importar publicações.",
                "No editor, toque no chip “Fontes”.",
                "Resolva ou baixe o que estiver faltando.",
            ),
            action = HelpAction.OPEN_LIBRARY,
            keywords = listOf("fontes", "biblioteca", "referências", "publicações", "download"),
            routes = setOf(Routes.HOME, Routes.EDITOR),
        ),
        HelpTopic(
            id = "tribuna",
            question = "O que é o Mapa mental (antiga Tribuna)?",
            shortAnswer = "É o mapa de memória da seção, com lembretes curtos, " +
                "âncora, referências e imagens. Serve para falar sem ler o texto.",
            steps = listOf(
                "Gere o mapa mental pelo Palco.",
                "Abra o mapa mental e edite os lembretes.",
                "Use Mapa/Foco no Palco durante a apresentação.",
            ),
            action = HelpAction.OPEN_TRIBUNA,
            keywords = listOf(
                "tribuna", "cues", "lembretes", "mapa", "memória", "palco", "apresentar",
            ),
            routes = setOf(Routes.PRESENT_HOME, Routes.TRIBUNA, Routes.EDITOR, Routes.PRESENT),
            requiresNote = true,
        ),
        HelpTopic(
            id = "imagens",
            question = "Como adiciono imagens à nota?",
            shortAnswer = "As imagens são anexos privados da nota ou da seção, " +
                "com legenda, tipo e ordem. Elas não entram no texto nem no RAG.",
            steps = listOf(
                "No editor, toque em “Imagens (N)” na seção.",
                "Adicione a imagem e escreva a legenda.",
                "Escolha o tipo e a ordem; toque para revisar.",
            ),
            action = HelpAction.OPEN_EDITOR,
            keywords = listOf("imagem", "foto", "gráfico", "legenda", "anexo"),
            routes = setOf(Routes.EDITOR, Routes.TRIBUNA, Routes.PRESENT),
            requiresNote = true,
        ),
        HelpTopic(
            id = "treino",
            question = "Como treino meu discurso?",
            shortAnswer = "O Treino lê o trecho escolhido, mostra o tempo e " +
                "pode analisar sua fala no aparelho.",
            steps = listOf(
                "Abra a aba Treino.",
                "Escolha o esboço e o trecho.",
                "Defina modo e cronômetro; toque em Começar treino.",
            ),
            action = HelpAction.OPEN_TRAIN,
            keywords = listOf("treino", "ensaio", "cronômetro", "microfone", "fala"),
            routes = setOf(Routes.TRAIN_HOME, Routes.STAGE),
        ),
        HelpTopic(
            id = "palco",
            question = "Como apresento no Palco?",
            shortAnswer = "O Palco mostra uma seção por vez, com lembretes, timer, " +
                "imagens e checkpoint de tempo.",
            steps = listOf(
                "Abra a aba Palco e escolha o esboço.",
                "Confira o pré-voo e toque em Entrar no palco.",
                "Navegue por Anterior/Próxima e use Mapa/Foco.",
            ),
            action = HelpAction.OPEN_PRESENT,
            keywords = listOf("palco", "apresentar", "apresentação", "timer", "mapa", "foco"),
            routes = setOf(Routes.PRESENT_HOME, Routes.PRESENT, Routes.TRIBUNA),
        ),
        HelpTopic(
            id = "voz-offline-privacidade",
            question = "Como funciona a voz? Meus dados sobem?",
            shortAnswer = "O TTS e o reconhecimento podem rodar no aparelho. " +
                "Áudio não é gravado; a transcrição é descartada após as métricas.",
            steps = listOf(
                "Abra Configurações → Voz.",
                "Escolha o motor e a voz.",
                "Teste a voz; o áudio nunca é salvo.",
            ),
            action = HelpAction.OPEN_VOICE,
            keywords = listOf("voz", "tts", "stt", "privacidade", "offline", "áudio", "dados"),
        ),
    )

    fun topic(id: String): HelpTopic? = topics.firstOrNull { it.id == id }
}

/** Busca e filtro do catálogo. Puro/testável. */
object HelpEngine {

    fun topicsFor(
        context: AssistantHelpContext,
        query: String = "",
        all: List<HelpTopic> = HelpCatalog.topics,
    ): List<HelpTopic> {
        val prioritized = errorTopics(context.errorKind)
        val q = normalize(query.trim())
        if (q.isBlank()) {
            return all
                .filter {
                    it.routes.isEmpty() || context.route in it.routes || it.id in prioritized
                }
                .sortedWith(
                    compareByDescending<HelpTopic> { it.id in prioritized }
                        .thenByDescending { context.route in it.routes }
                        .thenBy { it.id },
                )
        }
        return all
            .filter { topic ->
                normalize(
                    topic.question + " " + topic.shortAnswer + " " +
                        topic.keywords.joinToString(" "),
                ).contains(q)
            }
            .sortedWith(
                compareByDescending<HelpTopic> { it.id in prioritized }
                    .thenByDescending { context.route in it.routes }
                    .thenBy { it.question },
            )
    }

    private fun errorTopics(errorKind: HelpErrorKind?): Set<String> = when (errorKind) {
        HelpErrorKind.NO_KEY -> setOf("chave-ia")
        HelpErrorKind.MISSING_SOURCES -> setOf("fontes")
        HelpErrorKind.GENERATION -> setOf("copilot", "tribuna")
        HelpErrorKind.ANALYSIS -> setOf("treino", "voz-offline-privacidade")
        HelpErrorKind.OTHER, null -> emptySet()
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.forLanguageTag("pt-BR"))
            .trim()
    }
}

/** Regras dos gatilhos proativos. Puro/testável. */
object HelpTriggerEngine {

    fun decisionFor(id: HelpTriggerId): HelpTriggerDecision? = when (id) {
        HelpTriggerId.EMPTY_LIBRARY -> HelpTriggerDecision(
            id,
            "Crie ou importe seu primeiro esboço.",
            HelpAction.OPEN_CREATE,
        )
        HelpTriggerId.NO_KEY -> HelpTriggerDecision(
            id,
            "Para gerar texto com IA, configure sua chave.",
            HelpAction.OPEN_MODEL,
        )
        HelpTriggerId.MISSING_SOURCES -> HelpTriggerDecision(
            id,
            "Faltam fontes para este ponto.",
            HelpAction.OPEN_LIBRARY,
        )
        HelpTriggerId.ERROR_HELP -> HelpTriggerDecision(
            id,
            "Quer entender o que aconteceu?",
            HelpAction.OPEN_HELP_TOPIC,
        )
        HelpTriggerId.STAGE_TRIBUNA -> null
    }

    fun choose(
        candidates: Set<HelpTriggerId>,
        states: Map<HelpTriggerId, HelpTriggerState>,
        now: Long,
        onboardingSettled: Boolean,
        busy: Boolean = false,
        immersive: Boolean = false,
        sheetOpen: Boolean = false,
        shownThisSession: Boolean = false,
    ): HelpTriggerDecision? {
        if (!onboardingSettled || busy || immersive || sheetOpen || shownThisSession) return null
        return candidates
            .asSequence()
            .filter { it != HelpTriggerId.STAGE_TRIBUNA }
            .sortedBy { it.priority }
            .firstOrNull { id ->
                val state = states[id] ?: HelpTriggerState()
                !state.dismissed && !inCooldown(state, now)
            }
            ?.let(::decisionFor)
    }

    fun inCooldown(state: HelpTriggerState, now: Long): Boolean =
        state.lastShownAt > 0L && now - state.lastShownAt < HELP_TRIGGER_COOLDOWN_MS
}
