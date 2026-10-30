# 45 min monolítico — implementação e validação (2026-10-30)

**Status:** implementação na branch `feat/45min-monolitico`. O retry de
extensão foi corrigido no gerador e o BODY rico de 45 min passou no A34 com
**5.711 palavras (97,6%) nas 2 partes**, sem aviso de truncamento. O fluxo de
progresso, continuação, aceite e undo está validado; falta só o merge.

- Branch: `feat/45min-monolitico` (base `c228922`, main 2.8.2/27).
- Testes: **2.706 JVM / 0 falhas** (+38).
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
- **retry de extensão no gerador**: se a parte termina com `finish=stop` e
  fica abaixo de 80% da meta, uma segunda chamada reescreve a parte completa
  com `maxTokensOverride=8.192` (o provider só disparava isso para card de
  chat, e o draft de seção é JSON — o retry estava morto);
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
- continuação manual usa “CONTINUAÇÃO DIRIGIDA”: continue do final, não
  repita e **não escreva uma nova conclusão** (não é a última parte);
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

## Validação no A34 — nota vazia (stress)

Nota de teste: BODY 45 min sem subpontos, **sem outline/refs**
(título “TEMA 45 MIN”), via “texto total” (execução anterior ao retry de
extensão).

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

## Validação no A34 — BODY rico (gate de produto)

Nota de teste: BODY 45 min sem subpontos, com título, objetivo,
linha de raciocínio (5 pontos), textos bíblicos e `w25.07`.

- Parte 1: 1.286 palavras → retry de extensão no gerador com budget 8.192 →
  **4.463 palavras** (`draft-part-1-expand`).
- Parte 2: 706 palavras → retry de extensão → **1.183 palavras**
  (`draft-part-2-expand`).
- Total: **5.711 de ~5.850 palavras (97,6%)**; sheet **sem** aviso de
  truncamento, botão **Aceitar**.
- Aceitar → “Rascunho aplicado”; conteúdo final de 33.812 chars termina em
  uma conclusão única no fim (o texto tem “por fim” também como transição no
  meio, não como nova conclusão).
- A fidelidade marcou refs que o modelo citou com nomes não normalizados
  (ex.: “gênesis 3:6”); é informativo e não bloqueia.
- Nota de teste removida ao restaurar a DB limpa; #192 preservada.

## Limitações / decisão

- Nota vazia: o retry de extensão agora também roda nela; a execução antiga
  (3.586 + 2 continuações até 6.024) mostrou o fallback honesto. A continuação
  manual foi corrigida para não escrever nova conclusão, coberta por teste
  puro; a nota vazia com o prompt novo não foi reexecutada no A34.
- O BODY rico passou o gate de produto: **5.711 palavras** nas 2 partes.
- A fidelidade ainda sinaliza refs com nome por extenso vs abreviação; é
  informativo, não é bloqueio do 45 min.
- A34 cumpriu o fluxo; o smoke limpo arm64 da release 3.0.0 continua
  obrigatório.
- **Critério de merge atendido**: retry corrigido/testado, continuação sem
  nova conclusão, A34 rico ≥4.680, suíte/lint verdes. Próximo passo: merge.
