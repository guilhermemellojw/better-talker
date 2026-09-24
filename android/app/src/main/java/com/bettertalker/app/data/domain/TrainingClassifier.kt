package com.bettertalker.app.data.domain

import com.bettertalker.app.data.util.normalizeText

/**
 * Classificação de treinamento — mesmas regras da web (Fase 7).
 * Determinística; seção/título primeiro (sinal forte), corpo depois.
 * Padrões sem acento (normalizeText remove diacríticos). Inseguro = UNKNOWN.
 */
object TrainingClassifier {

    private val rules: List<Pair<TrainingCategory, Regex>> = listOf(
        TrainingCategory.INTRODUCTION to Regex("(introducao|introduzir|abertura|comeco|comecar|iniciar|inicio|primeira impressao)"),
        TrainingCategory.QUESTIONS to Regex("(pergunta|questionar|perguntas de)"),
        TrainingCategory.DEVELOPMENT to Regex("(desenvolvimento|desenvolver|pontos principais|estrutura|esboco|corpo do discurso)"),
        TrainingCategory.EXPLANATION to Regex("(explicacao|explicar|expor|ensino|ensinar)"),
        TrainingCategory.ILLUSTRATION to Regex("(ilustra|exemplo|analogia|historia|experiencia)"),
        TrainingCategory.APPLICATION to Regex("(aplicacao|aplicar|pratica|licao pratica)"),
        TrainingCategory.TRANSITION to Regex("(transicao|transicoes|ligar|ponte|passar para)"),
        TrainingCategory.CONCLUSION to Regex("(conclusao|concluir|terminar|encerramento|recapitular|final)"),
        TrainingCategory.CLARITY to Regex("(clareza|claro|simples|simplicidade|direto|conciso)"),
        TrainingCategory.NATURALNESS to Regex("(naturalidade|natural|modestia|sinceridade|calma)"),
        TrainingCategory.DELIVERY to Regex("(entrega|voz|gestos?|pausas?|ritmo|palco|volume|diccao|leitura|contato visual)")
    )

    fun classify(section: String?, title: String?, text: String?): TrainingCategory {
        val heading = normalizeText("${section.orEmpty()} ${title.orEmpty()}".take(300))
        if (heading.isNotBlank()) {
            for ((cat, re) in rules) if (re.containsMatchIn(heading)) return cat
        }
        val body = normalizeText((text.orEmpty()).take(2000))
        if (body.isBlank()) return TrainingCategory.UNKNOWN
        for ((cat, re) in rules) if (re.containsMatchIn(body)) return cat
        return TrainingCategory.UNKNOWN
    }

    /** Categoria efetiva: gravada prevalece (lixo vira UNKNOWN); senão classifica on-the-fly (backfill). */
    fun effective(stored: String?, section: String?, title: String?, text: String?): TrainingCategory {
        if (!stored.isNullOrBlank()) return TrainingCategory.fromSerial(stored)
        return classify(section, title, text)
    }
}
