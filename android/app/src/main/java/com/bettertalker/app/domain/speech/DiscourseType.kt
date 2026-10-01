package com.bettertalker.app.domain.speech

/**
 * Tipo de discurso/parte. **Metadado informativo, não restritivo.**
 *
 * Desde a 3.2.5f (autonomia), o DiscourseType NÃO impõe estrutura:
 * - O usuário pode adicionar/remover/mudar roles livremente
 * - A validação ([SpeechSectionValidator]) só emite aviso (não bloqueia)
 * - O editor é agnóstico ao tipo (lê `role` de cada seção)
 *
 * O tipo serve para:
 * - Contexto do Copilot (prompt por tipo — Fase 4)
 * - Estatísticas e filtros
 * - Estrutura inicial sugerida (futuro onboarding)
 *
 * O `require` do OutlineConverter (tipos curtos exigem 1 section) é
 * pré-condição do IMPORT, não do CRUD do usuário.
 *
 * Estruturas típicas (sugestão de import/onboarding; o CRUD não restringe):
 * - [S34_DISCOURSE]: discurso público de 30-45 min (S-34-T):
 *   INTRO + N BODY (com sub-pontos) + CONCLUSION.
 * - [TREASURES_TALK]: Tesouros da Palavra de Deus (10 min):
 *   1 BODY único; sub-pontos representam os 2-3 pontos da apostila.
 * - [MINISTRY_PART]: Faça Seu Melhor no Ministério (3-5 min):
 *   1 BODY único; sub-ponto único representa o ponto de estudo.
 * - [AVULSO]: parte standalone (leitura da Bíblia, discurso avulso):
 *   1 BODY único sem sub-pontos obrigatórios.
 */
enum class DiscourseType {
    S34_DISCOURSE,
    TREASURES_TALK,
    MINISTRY_PART,
    AVULSO,
    ;

    companion object {
        /**
         * Resolve o nome persistido (coluna `discourseType`) para o enum.
         * Valor desconhecido/nulo cai em [S34_DISCOURSE] (nunca quebra leitura).
         */
        fun fromNameOrDefault(name: String?): DiscourseType =
            values().firstOrNull { it.name == name } ?: S34_DISCOURSE
    }
}
