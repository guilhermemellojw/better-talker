package com.bettertalker.app.domain.planning

/**
 * Pedido de geração de uma proposta de esboço para um ângulo específico.
 *
 * Montado pelo [OutlineProposer] a partir do tema e do resultado do retrieval;
 * consumido por um [OutlineGenerator] (LLM real na Fase 2, fake nos testes).
 *
 * @param id identificador gerado pelo caller (via idProvider do propositor).
 * @param theme tema do discurso.
 * @param totalMinutes duração total alvo em minutos.
 * @param audience público-alvo.
 * @param angle ângulo desta geração.
 * @param bibleRefs referências bíblicas encontradas pelo retrieval.
 * @param publicationRefs referências a publicações encontradas pelo retrieval.
 * @param methodPrinciples princípios de be/th encontrados pelo retrieval.
 */
data class OutlineGenerationRequest(
    val id: String,
    val theme: String,
    val totalMinutes: Int,
    val audience: Audience,
    val angle: OutlineAngle,
    val bibleRefs: List<String>,
    val publicationRefs: List<PublicationRef>,
    val methodPrinciples: List<String>,
    /**
     * F5b: conteúdo de origem (artigo colado). Quando presente, o LLM
     * estrutura ESTE conteúdo em vez de gerar do tema. Default null mantém
     * o caminho theme-driven do #3c.
     */
    val sourceContent: String? = null,
    /**
     * #8b: natureza do material colado (ex: "TALK:10min") — quando presente,
     * o prompt pede um discurso COMPLETO com a duração da parte V&M.
     */
    val materialNature: String? = null,
)
