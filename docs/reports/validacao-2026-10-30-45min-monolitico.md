# 45 min monolítico — implementação e validação (2026-10-30)

**Status:** implementação na branch `feat/45min-monolitico`, **sem merge**.
O fluxo de 2 partes, progresso, continuação, aceite e undo foi validado no
A34. O alvo de ≥4.680 palavras já nas 2 partes **não** foi atingido na nota
de teste vazia; o sheet ofereceu continuação e chegou a 6.024 palavras.

- Branch: `feat/45min-monolitico` (base `c228922`, main 2.8.2/27).
- Testes: **2.703 JVM / 0 falhas** (+35).
- `lintRelease`: **verde**.
- Sem bump/tag; release intocado.

## O que mudou

### Modelo puro

`SectionDraftPlan` (`domain/planning`):
- `SINGLE | BY_SUBPOINT | CONTINUATION`;
- 45 min BODY sem subpontos → **2 partes, 23 + 22 min, 2.990 + 2.860 palavras**
  (soma 5.850);
- 30 min → 1 parte; 20 min com subpontos → BY_SUBPOINT;
- distribuição sem perder minuto (45/4 = 12+11+11+11).

`LongTextRequest` ganhou `partIndex`/`partCount`; o orçamento de uma parte usa
a **seção** (45 min → 4.488 tokens por parte), não a fatia de minutos.
`DraftProgress` carrega parte, total, meta e palavras já escritas.

### Orquestração

`SectionGeneratorImpl`:
- parte 1: prompt “Parte 1 de 2; comece; não conclua; transição”;
- parte 2: inclui o final da parte 1 (~500 chars) e “continue daqui; não
  repita; conclua”;
- cada parte tem seu `LongTextRequest` (23/22 min, section 45, metas 2.990/
  2.860, partIndex/partCount);
- retry de length até 8.192 (provider agora com 2 tentativas de qualidade);
- timeout de 180 s para partes longas;
- stitch puro: junta `<p>`, remove parágrafo repetido consecutivo e corta na
  última frase completa (fecha `<p>/<strong>/<em>`);
- fidelidade e `usedSources` checados no texto final;
- `possiblyTruncated` só se a última parte cortou ou o total ficou <80%;
- se uma parte corta o JSON, `parsePartial` recupera o `text` até onde deu em
  vez de descartar tudo.

### DraftSheet/EditorViewModel

- Loading mostra “Parte 1 de 2 · ~2.990 palavras”, barra de progresso e
  “N escritas”;
- continuação mostra “Continuação · ~1.455 palavras · 4.395 escritas”;
- Ready mostra total/meta, aviso de encurtamento e:
  - **Continuar geração** quando curto/cortado;
  - **Aceitar** quando completo;
  - **Aceitar mesmo assim** quando parcial;
- um único aceite por seção; undo restaura o HTML anterior;
- aviso de falha ao continuar preserva o draft atual;
- TalkBack anuncia a troca de parte uma vez.

### 45 min com subpontos

Não mudou: a fila por subponto (5 alvos + intro/conclusão) continua igual.
O caminho monolítico é o BODY sem subpontos via “texto total”/seção.

## Validação no A34 (1080x2340)

Nota de teste: um BODY de 45 min sem subpontos, **sem outline/refs**
(título “TEMA 45 MIN”), via “texto total”.

- Loading: “Parte 1 de 2 · ~2.990 palavras”.
- Parte 1: finish=stop, **1.636 palavras**.
- Parte 2: finish=stop, **1.911 palavras**; total **3.586** (61% de 5.850).
- Sheet: “3.586 de ~5.850 palavras”, aviso de encurtamento,
  **Continuar geração** + **Aceitar mesmo assim**.
- Continuar geração 1: +809 → **4.395** palavras.
- Continuar geração 2: +1.629 → **6.024** palavras; botão **Aceitar**
  (sem aviso de corte).
- Fidelidade: flag de “2 timóteo 4:2” e “2 pedro 1:12” — esperado na nota
  vazia, sem fontes; o card é informativo e não bloqueia.
- Aceitar → “Rascunho aplicado”; Desfazer → seção volta a **0 palavras**.
- Cancelar: dismiss do sheet durante uma geração posterior; nenhuma parte
  nova concluída depois (job cancelado), sem crash.
- Nota de teste removida ao restaurar a DB limpa; #192 e o histórico anterior
  preservados.

## Limitações / decisão

- O alvo de **≥4.680 palavras em 2 partes** não foi atingido na nota vazia:
  o modelo escreveu 3.586. O comportamento é honesto (marca truncado e oferece
  continuação), mas a meta de 2 partes só deve ser esperada com um BODY com
  conteúdo/linha de raciocínio; a nota de teste não tinha material.
- O frame de validação usa a build da branch; o rótulo de continuação foi
  corrigido depois e coberto por teste puro.
- A34 cumpriu o fluxo; o smoke limpo arm64 da release 3.0.0 continua
  obrigatório.
- **Sem merge** até decisão: aceitar o fallback de continuação como gate ou
  repetir com um BODY real/rico para buscar 4.680+ nas 2 partes.
