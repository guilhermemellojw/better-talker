# Re-Validação Final F20-F1 — A34 (2026-10-05)

## Ambiente
- **Device:** SM-A346M (USB)
- **APK:** `bff1dac` (correções #9/#10) — install Success
- **Chave DeepSeek:** presente e válida (`sk-`; provider `auto`)

## Bloco A — Bug #9 (S-34 via filename): **FALHOU (por Bug #12)**
- **A detecção foi corrigida e funcionou**: o hook rodou e **criou o
  attachment** `att-7e2d57f5…` (`s34-35.docx`, indexed=false, ready) — evidência
  `S34Import: chat import outcome=… attachment=att-7e2d57f5…`.
- **Mas o parser falhou**: `S34Import: S34 parse failed … reason=sem pontos
  reconhecidos`.
- Tabelas: `s34_outlines=0`, `s34_sections=0`, `s34_subsections=0`,
  `s34_references=0` (esperado 1/5/28/≥20).
- **Causa (Bug #12, novo P0):** `S34Parser.POINT_RE` exige pontos **numerados**
  (`^\s*\d{1,2}[.)]`); o S-34 real (N.º 35) usa **seções com título sem
  número** (“FOMOS CRIADOS… (5 min)”) → nenhum ponto reconhecido. O
  `OutlineParser` legado lida com esse formato (5 seções/28 subpontos), mas o
  parser estruturado não.
- **Oratória não degrada: NÃO** — “Desenvolva o ponto 1” caiu no chat (resposta
  com `〈sugestão〉`), como esperado sem S-34 estruturado.

## Bloco E — Bug #10 (foco do “Criar proposta”): **BLOQUEADO (por Bug #13)**
- A resposta de criação aparece com **💡 Sugestiva criativa** no chat (badge ok).
- **Os botões de ação (“Criar proposta”/“Inserir no tópico”) da última resposta
  longa não são alcançáveis**: com o scroll no fim, o texto termina colado na
  barra de entrada e os botões não aparecem na UI (nem no dump de
  acessibilidade, mesmo após scroll máximo).
- **Bug #13 (novo, P1):** botões de ação inacessíveis em respostas longas /
  última mensagem — bloqueia o fluxo de proposta independentemente do fix do
  foco (T2 `bff1dac`, que está no código e coberto por teste).
- Não foi possível validar Cartão/Aceitar/Desfazer nesta sessão.

## Bugs novos
- **#12 (P0):** `S34Parser` exige pontos numerados; S-34 reais com seções sem
  numeração falham (“sem pontos reconhecidos”) → `s34_*` não persistem
  (bloqueia o pipeline estruturado para esse formato).
- **#13 (P1):** botões de ação do chat inacessíveis na última resposta longa →
  “Criar proposta” não é tocável.

## Confirmações positivas
- **T1 (#9) verificado no device:** detecção por filename funcionou (attachment
  criado; hook executado).
- **Badge do Modo Criação** continua correto no chat (💡, tags ocultas).
- Gate ativo (log: “verificador removeu 1 trecho(s) sem apoio”).

## State
- Nenhum código alterado durante a validação.
- Re-validação final: **A falhou (Bug #12)**; **E bloqueado (Bug #13)**;
  correções #9/#10 confirmadas no código e unitariamente.
