# Card-first — estados A34-only (2026-10-28)

- Branch: `fix/card-first-estados` (base `23df2fd`).
- Commit de código: `4b283fe`; relatório: `61c6e1d` + este ajuste.
- Suíte: **2.607 testes / 0 falhas**. `lintRelease`: **verde**.
- CI: **verde** (run `40669479048`).
- A34: Samsung Galaxy A34 (SM-A346M), Android 16 / API 36. Validado em
  2026-10-28, após o aparelho voltar à rede.

## O que foi fechado no código

- `NO_TARGET` / `USER_PICK`: `applyUserPick` puro captura o `baseHash` no
  momento da escolha, grava `capturedFrom=USER_PICK`/`capturedAt` e volta
  para `AVAILABLE`; aviso de revisão; seletor explica nota sem tópicos.
- `TARGET_MISSING`: status próprio, sem insert/replace/gate; aviso e menu
  com Copiar/Dispensar.
- `FAILED`: “Tentar novamente” repete a ação que falhou (insert ou undo);
  rótulo “Falha na ação”.
- `CANCELLED` / `ERROR`: payload do turno cancelado sem chaves `card*`;
  parcial read-only, sem insert/gate; retry abre novo turno.
- `SUPERSEDED`: card sem alvo não entra na marcação; matriz pura de ações
  por estado.

## Validação no A34

Os estados foram isolados com mensagens de teste no banco do aparelho
(apenas para o smoke; o banco original foi restaurado depois).

### `NO_TARGET` / `USER_PICK`

- Card `NO_TARGET` exibiu “Escolha o destino” e “Escolher destino”.
- O seletor listou os tópicos reais (`FOMOS...`, `COMO A VIDA...`, etc.).
- Ao escolher `FOMOS CRIADOS...`:
  - `cardTargetKind=SECTION`, `cardTargetId=c987ef29...`;
  - `cardBaseHash=8c9c1cae` (hash do conteúdo naquele momento);
  - `cardCapturedFrom=USER_PICK`, `cardCapturedAt` preenchido;
  - aviso “Esta sugestão foi gerada sem o contexto deste tópico. Revise
    antes de inserir.”
- “Adicionar ao final” inseriu no tópico: `5.922 → 5.954` chars,
  `cardStatus=APPLIED`; o undo restaurou `5.922` chars e `AVAILABLE`.

### `TARGET_MISSING`

- Card exibiu “Destino não existe mais” e o aviso “Copie o texto ou
  dispense a sugestão”.
- Nenhum botão de insert/replace foi exibido.
- O menu mostrou Editar/Verificar/Copiar/Dispensar, **sem** Desfazer/insert.

### `NOTE` (discurso inteiro)

- Geração real no escopo “Discurso inteiro” produziu card
  `cardTargetKind=NOTE`, alvo `note-2645c5cb...`, `cardCapturedFrom=WHOLE`.
- **Append**: `richHtml` da nota `5.947 → 6.391` chars, `APPLIED`;
  undo restaurou `5.947`.
- **Substituir** com confirmação explícita (“O conteúdo atual de «#35...»
  será substituído...”): `richHtml 5.947 → 37` chars, `APPLIED`;
  undo restaurou `5.947`.
- `mdText`/`plainText` permaneceram intactos, como definido para o alvo
  `NOTE` (o card escreve no `richHtml`).

### `FAILED`

- Card `FAILED` exibiu “Falha na ação” + “Tentar novamente”.
- O retry executou o append no alvo (`Introdução`, 0 → 35 chars),
  `cardStatus=APPLIED`, ação `APPEND`, `undo=true`.

### `CANCELLED` / `ERROR`

- Mensagem cancelada persistida com parcial de card:
  “Parcial interrompido do card.”, “Pensou por 3s” e “Geração parada.”.
- Nenhum botão de insert/replace e nenhum gate de card; apenas
  “Tentar novamente”/“Fechar” do turno.
- O payload persistido não tem chaves `card*` (teste puro também cobre).

## Observações

- O banco de teste foi restaurado ao estado anterior ao smoke; as
  mensagens injetadas ficaram inativas.
- A validação de `NO_TARGET`/`TARGET_MISSING`/`FAILED`/`CANCELLED` foi de
  **estado/UI/ações** com cards injetados; a geração real foi usada para
  `NOTE` e para os fluxos de seção já validados na fase 1.
- Tablet segue pendente (gate da tag); keystore também.
