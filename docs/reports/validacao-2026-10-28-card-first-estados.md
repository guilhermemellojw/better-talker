# Card-first — estados A34-only (2026-10-28)

- Branch: `fix/card-first-estados` (base `23df2fd`, sem merge).
- Commit: `d689ec1`.
- Suíte: **2.606 testes / 0 falhas**. `lintRelease`: **verde**.
- CI: **verde** (run `40662478026`).

## O que foi fechado no código

- `NO_TARGET` / `USER_PICK`:
  - extraído `applyUserPick` (puro): captura `baseHash` do conteúdo no
    momento da escolha, grava `capturedFrom=USER_PICK`, `capturedAt` e
    volta para `AVAILABLE`;
  - teste puro cobre hash, destino, origem e timestamp;
  - o card mostra o aviso “gerada sem o contexto deste tópico” depois da
    escolha;
  - o seletor agora explica quando a nota não tem tópicos disponíveis.
- `TARGET_MISSING`:
  - o card mostra “O destino não existe mais. Copie o texto ou dispense”;
  - sem insert/replace/gate; menu mantém Copiar/Dispensar;
  - testes puros de status, ações e gate.
- `FAILED`:
  - o “Tentar novamente” agora repete a ação que falhou (insert **ou**
    undo), em vez de sempre fazer append;
  - rótulo “Falha na ação” (cobre insert e undo);
  - testes puros de ações/gate e do rótulo.
- `CANCELLED` / `ERROR`:
  - o payload do turno cancelado continua sem chaves `card*`; teste puro
    garante que ele não vira card acionável. O parcial fica congelado e
    read-only; retry abre novo turno.
- `SUPERSEDED`:
  - card sem alvo não entra na marcação de “sugestão anterior”; teste puro.

## Ainda pendente no A34

O A34 ficou inacessível durante esta rodada (`192.168.3.5` sem rota e sem
mDNS). Os fluxos abaixo continuam precisando do smoke no aparelho:

1. gerar card sem alvo → escolher destino → revisar → inserir;
2. gerar card, apagar a seção, voltar ao card → “destino removido”;
3. escopo “Discurso inteiro” → card `NOTE` → append/replace/undo;
4. parar durante streaming de card → parcial read-only, sem gate; retry;
5. simular falha de insert/undo (se possível) e conferir “Tentar novamente”.

O código não foi mergeado na `main` até esse smoke.
