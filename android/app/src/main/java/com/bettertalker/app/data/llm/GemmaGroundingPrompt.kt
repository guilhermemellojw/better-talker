package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.INSUFFICIENT_EVIDENCE_MESSAGE

/**
 * F2.1 — bloco curto de grounding anexado ao system do Gemma local.
 *
 * Não duplica as regras do `ChatPromptBuilder` (que já vão no prompt): só
 * reforça o princípio — a fonte controla o conteúdo, o modelo controla a
 * expressão — e reutiliza a frase de insuficiência do produto.
 */
val GEMMA_GROUNDING_BLOCK = """
REGRAS DE FIDELIDADE (Better Talker):
- A fonte controla o conteúdo; você controla apenas a expressão. Pode reescrever, explicar, criar transições e ilustrações, adaptar o tom e tornar a linguagem natural.
- NUNCA crie doutrinas, referências ou fatos novos; nunca atribua afirmações às publicações sem apoio explícito nas fontes; nunca substitua o conteúdo das fontes pelo seu conhecimento próprio.
- Se as fontes não sustentarem a resposta, diga exatamente: "$INSUFFICIENT_EVIDENCE_MESSAGE"
""".trimIndent()
