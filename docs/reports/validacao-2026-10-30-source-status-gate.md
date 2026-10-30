# Status único das fontes + gate determinístico (2026-10-30)

**Branch:** `feat/source-status-gate` (a partir de `main` `526e7c6`)
**Versão:** 2.8.2/27 — sem bump/tag
**Escopo:** checklist, prompt, gate do Copilot e card de fontes necessárias.

## Problema

O checklist e o prompt tinham dois caminhos de status:

- `NoteSourcesResolver` (checklist) distinguia edição ausente, trecho ausente,
  download, indexação e falha;
- `citedSources` usava `RoomReferenceResolver` e podia reduzir tudo a
  `MISSING_CORPUS`/`UNRESOLVED`, inclusive quando a edição estava baixando ou
  a indexação havia falhado.

O modelo podia então dizer que a referência “não existe” mesmo com a edição
no acervo, ou responder sem o trecho que a UI mostrava como disponível.

## Modelo único

`data/util/CitationStatus.kt`:

| Dimensão | Estados |
|---|---|
| Edição | `MISSING`, `DOWNLOADING`, `INDEXING`, `INDEX_FAILED`, `UNSUPPORTED`, `READY` |
| Texto | `RESOLVED`, `PARTIAL`, `NOT_FOUND`, `AMBIGUOUS` |
| Ação | `DOWNLOAD`, `REINDEX`, `CONFIRM`, `OPEN`, `WAIT`, `NONE` |

`NoteSource` agora carrega `CitationStatus` (edição + texto) e expõe
`status` legado apenas como compatibilidade. `SourceAction` é derivado:

- edição ausente → baixar;
- baixando/indexando → aguardar;
- indexação falhou → reindexar;
- formato não suportado/trecho parcial → abrir edição;
- referência ambígua → confirmar edição;
- edição pronta + trecho não encontrado → reindexar;
- pronto → nenhuma.

## Fonte única

- `NoteSourcesResolver` continua sendo o único classificador do checklist.
- `CopilotRepository.citedSources(noteSources)` usa a MESMA lista para decidir
  o que tem texto e o que está bloqueado. Só chama o resolvedor de trecho para
  fontes `READY`; o motivo/ação vem do `CitationStatus`.
- `buildTurnFor` calcula `noteSources` ANTES de `citedSources` e passa a lista
  pronta para o caminho do Copilot.
- O inventário do prompt (`NoteRefsInventory`) passou a levar, por ref:
  motivo real + ação, com a instrução “NÃO diga que uma referência não existe;
  NUNCA invente o conteúdo nem use outra edição”.
- O bloco `REFERÊNCIAS … SEM TEXTO` de `ChatPromptBuilder` recebeu a mesma
  instrução.

## Gate determinístico

`data/copilot/SourceGate.kt` (puro/testável):

- pergunta específica que cita ref bloqueante → `Ask` antes do LLM;
- pedido amplo → `Proceed` com aviso (motivo + ação);
- ref resolvida ou dispensada → `Proceed`;
- `be`/`th` nunca bloqueiam;
- “Continuar sem” registra as refs em `FONTES NÃO CONSULTADAS` no prompt.

`CopilotViewModel`:

- expõe `sourcesNeeded` (estado da tela, não mensagem);
- observa `attachmentDao().observe()` e reavalia o card e o checklist após
  download/indexação/reindexação;
- “Já baixei — atualizar” revalida e retoma a pergunta quando destrava;
- “Reindexar” enfileira os anexos das refs;
- “Continuar sem” retoma a pergunta original com o bloco de ausência.

`ChatScreen` ganhou `SourcesNeededCard` acima do composer, com ações de baixar
(no site ou direto), reindexar, confirmar edição, acompanhar, “Já baixei —
atualizar” e “Continuar sem”. Não depende de `chatSetupSeen`.

`SourcesSheet` usa o mesmo rótulo derivado do `CitationStatus`.

## Testes

- **2.734 JVM / 0 falhas** (+28 sobre 2.706)
- `CitationStatusTest` (10)
- `SourceGateTest` (11)
- `ChatPromptTest` (4)
- `NoteSourcesResolverTest` (+2)
- `NoteRefsCollectorTest` (+1)
- `lintRelease` verde

## Cenários de aceitação (previstos)

| Cenário | Edição | Texto | Ação |
|---|---|---|---|
| S-34 (refs do esboço) | conforme acervo | conforme trecho | conforme status |
| V&M (`mwb25.07`) | `MISSING` (sem arquivo) | `NOT_FOUND` | `DOWNLOAD` |
| `w25.07 22 §§ 8-9` | `READY` | `RESOLVED`/`NOT_FOUND` | `NONE`/`REINDEX` |
| `lmd lição 7 § 4` com arquivo `failed` | `INDEX_FAILED` | `NOT_FOUND` | `REINDEX` |
| edição ausente (`w24.01 § 3`) | `MISSING` | `NOT_FOUND` | `DOWNLOAD` |
| `g 1/16` | `MISSING` | `AMBIGUOUS` | `CONFIRM` |
| download → indexação | `DOWNLOADING` → `INDEXING` → `READY` | `NOT_FOUND` → `RESOLVED` | `WAIT` → `NONE` |

## Validação no A34 (SM-A346M, 1080x2340)

DB de teste com nota `# Teste gate de fontes` (S-34 copiado do #192) e refs
`w25.07 22 §§ 8-9`, `w25.07 22 § 999`, `lmd lição 7 ponto 4`, `w24.01 § 3`,
`g 1/16`, `mwb25.07` e `Gên 3:6`.

| Cenário | Resultado no A34 |
|---|---|
| S-34 / inventário | checklist mostrou **18 refs** (inclui as do esboço S-34); o prompt recebeu o inventário com motivo/ação |
| `w25.07 22 §§ 8-9` | `READY/RESOLVED`; **sem card**; resposta disse “está no acervo e o trecho foi resolvido” |
| `w25.07 22 § 999` | card `READY/NOT_FOUND`, “edição no acervo; trecho não encontrado”, **Ação: reindexar** |
| `lmd lição 7 ponto 4` | card `INDEX_FAILED/NOT_FOUND`, “edição importada; indexação falhou”, **Ação: reindexar** |
| `w24.01 § 3` | card `MISSING/NOT_FOUND`, “edição ausente do acervo”, **Ação: baixar edição**; botões “Baixar no jw.org” e “Baixar direto” |
| `g 1/16` | card `MISSING/AMBIGUOUS`, “Referência ambígua: g 1/16 pode ser janeiro/2016 ou edição 1/2016”, **Ação: confirmar edição**; botão “Corrigir referência” |
| `mwb25.07` (V&M) | card `MISSING/NOT_FOUND`, **Ação: baixar edição** |
| `Gên 3:6` | `READY/RESOLVED`; **sem card**; resposta gerada |
| “Continuar sem” | card sumiu; resposta do lmd disse “foi importada, mas a indexação falhou — o conteúdo não está disponível”, sem inventar texto |
| Auto-refresh | `attachmentDao().observe()` reavaliou o card após reindexar; o worker do w25.07 terminou em ~35 ms, então o estado `INDEXING` não ficou visível na captura; o card voltou ao estado real |

Observação: o teclado/adb insere espaço depois do ponto (“w25. 07”) e o
detector não reconhece essa forma; os testes de w25.07 no device usaram
“Sentinela de julho de 2025”, que resolve a mesma edição.

## Pendência

- Release 3.0.0/31 continua bloqueada até voz/STT + keystore + smoke limpo
  arm64. O A34 foi restaurado com a DB limpa após os testes.
