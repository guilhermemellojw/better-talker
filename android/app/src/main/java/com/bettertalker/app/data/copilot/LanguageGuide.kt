package com.bettertalker.app.data.copilot

/**
 * Fase 3 — guia de linguagem simples sem perder os termos técnicos TJ.
 *
 * É injetado no system prompt das rotas de chat e geração. A regra é
 * **simplificar a forma, nunca a doutrina**: nomes, publicações, papéis e
 * termos da organização permanecem exatamente como são.
 */

val TJ_TERMS_TO_PRESERVE = """
Jeová, Deus, Cristo, Jesus, espírito santo, Reino, Paraíso, Armagedom,
ressurreição, Escrituras Hebraicas, Escrituras Gregas Cristãs, as Escrituras,
corpo governante, escravo fiel e discreto, congregação, anciãos, superintendente,
circuito, assembleia, pioneiro, publicador, pregação, testemunho informal,
de casa em casa, revisita, estudo bíblico, morador, ajudante, estudante,
Tesouros da Palavra de Deus, Faça Seu Melhor no Ministério, Nossa Vida Cristã,
joias espirituais, leitura da Bíblia, consideração, demonstração, discurso,
A Sentinela, Despertai!, Ame as Pessoas, Melhore, brochura, lição, ponto, apêndice,
princípio bíblico, texto bíblico, relato bíblico, profecia, promessa, esperança,
fé, amor, lealdade, adoração
""".trimIndent()

val ACADEMIC_TERMS_TO_AVOID = """
outrossim, porquanto, destarte, conquanto, mormente, exórdio, arremate, peroração,
exegese, hermenêutica, homilética, retórica, supracitado, aludido, precitado,
inexorável, imperioso, premente, verbete, tratado, dissertação, conspícuo,
cônscuo, sobejamente, ilação, silogismo
""".trimIndent()

val LANGUAGE_PRINCIPLES = """
## DIRETRIZES DE LINGUAGEM

Público inclui oradores de diferentes níveis de escolaridade.
Use linguagem simples, clara e calorosa, sem perder profundidade.

FORMA:
- Frases curtas (máximo ~15 palavras).
- Voz ativa ("Jeová criou", não "foi criado por Jeová").
- Segunda pessoa ("você", "nós").
- Exemplos do cotidiano (família, trabalho, natureza).
- Aplicação prática no fim.
- Explique termos técnicos de oratória em 1 frase prática.

PRESERVE (nunca traduza):
$TJ_TERMS_TO_PRESERVE

EVITE:
$ACADEMIC_TERMS_TO_AVOID

NÃO faça:
- Trocar "Jeová" por "Deus".
- Trocar "as Escrituras" por "a Bíblia".
- Trocar "publicador" por "membro".
- Simplificar doutrina (só a forma).
- Remover citações bíblicas.
- Transformar discurso em conversa.
""".trimIndent()
