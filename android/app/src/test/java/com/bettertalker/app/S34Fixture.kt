package com.bettertalker.app

/**
 * Fase 19-B.1 — fixture anonimizada de S-34 (test sources only).
 *
 * DADOS DE TESTE SINTÉTICOS — não é um esboço oficial, não contém trechos
 * de publicações, apenas texto estrutural inventado + referências curtas
 * (versículos e códigos de edição) como identificadores.
 *
 * Propriedades deliberadas (§§7-10 da fase):
 * - marcador S-34 + tema + bloco de objetivo;
 * - 3 pontos numerados e temporizados, em ordem textual;
 * - subideias a)/b) no ponto 2;
 * - 3 textos bíblicos (um por ponto) e 2 refs de publicação (pontos 1-2);
 * - SEM seções "INTRODUÇÃO"/"CONCLUSÃO" (o S-34 pode não tê-las).
 */
object S34Fixture {
    const val TEXT = """S-34 — TEXTO SINTÉTICO PARA TESTE (não é um esboço oficial)

Tema: Como fortalecer a fé

Objetivo:
Mostrar como a fé pode ser fortalecida por meio da Palavra de Deus.

1. A fé precisa de uma base sólida (4 min)
   Leia João 17:17.
   Consulte a publicação de estudo w24.01, §3.

2. A fé cresce quando colocamos em prática o que aprendemos (5 min)
   a) Estudar regularmente
   b) Aplicar o que aprendemos
   Leia Tiago 2:17.
   Consulte a publicação de estudo w24.02, §5.

3. Continue fortalecendo sua fé (3 min)
   Leia Hebreus 10:23.
"""

    /**
     * T1 (Bug #12) — formato REAL (como o No. 194-T): seções com título +
     * `(N min)` e SEM numeração (título case, às vezes entre aspas), com
     * subpontos a)/b) e refs. Conteúdo SINTÉTICO, como o [TEXT].
     */
    const val TIMED_TITLES = """S-34 — ESBOÇO SINTÉTICO PARA TESTE (não é um esboço oficial)

Tema: Como cultivar paciência

Objetivo:
Mostrar por que a paciência ajuda nas decisões do dia a dia.

"A paciência se prova nas pequenas escolhas" (3 min)
  Quem espera antes de responder evita muitos conflitos. (Tiago 1:19)
  Consulte a publicação w24.01, §4.

A paciência ajuda nos estudos (5 min)
  a) Reservar tempo fixo para estudar (Provérbios 21:5)
  b) Revisar o que aprendeu
  Leia Provérbios 14:29.

A paciência ajuda nos relacionamentos (5 min)
  Ouvir com calma antes de opinar fortalece os vínculos.

A paciência nos ajuda em tempos difíceis (15 min)
  As dificuldades passam; a calma ajuda a decidir melhor.

Continue cultivando paciência (2 min)
  Leia Hebreus 10:36.
"""
}
