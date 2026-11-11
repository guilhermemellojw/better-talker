package com.bettertalker.app.ui.copilot

/**
 * Resultado tipado da geração da Tribuna.
 *
 * O editor só precisa saber se gerou; o Palco usa [Failure] para explicar o
 * motivo e oferecer as ações certas (tentar de novo, abrir o editor quando
 * falta texto/seções ou configurar a IA quando falta a chave BYOD).
 */
sealed interface TribunaResult {
    data object Success : TribunaResult

    data class Failure(
        val message: String,
        val canOpenEditor: Boolean = false,
        val canConfigureAi: Boolean = false,
    ) : TribunaResult
}

/**
 * Falhas conhecidas da geração, com as ações que fazem sentido em cada caso.
 * Puro/testável e usado também nas mensagens do chat do editor.
 */
internal object TribunaFailures {

    fun noNote(): TribunaResult.Failure =
        TribunaResult.Failure("Não foi possível identificar a nota. Escolha o esboço novamente.")

    fun noAiKey(): TribunaResult.Failure =
        TribunaResult.Failure(
            message = "Configure a chave de IA na tela Modelo IA para gerar o mapa mental.",
            canConfigureAi = true,
        )

    fun emptySections(): TribunaResult.Failure =
        TribunaResult.Failure(
            message = "As seções ainda estão vazias — desenvolva o discurso antes de gerar o mapa mental.",
            canOpenEditor = true,
        )

    fun providerFailed(): TribunaResult.Failure =
        TribunaResult.Failure("Não consegui gerar o mapa mental. Tente novamente.")

    fun truncated(): TribunaResult.Failure =
        TribunaResult.Failure(
            message = "O mapa mental foi cortado pelo limite de tokens. " +
                "Tente novamente com menos seções.",
            canOpenEditor = true,
        )

    fun emptyResponse(): TribunaResult.Failure =
        TribunaResult.Failure(
            "A resposta do mapa mental veio vazia ou em formato inesperado. Tente novamente.",
        )
}
