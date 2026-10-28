# Card-first — atomicidade do insert/undo (2026-10-28)

- Branch: `fix/card-atomicity` (base `065132b`).
- Objetivo: impedir duplicação quando a escrita do alvo succeeds e a
  atualização do card falha (ou o processo morre no meio).
- Suíte: **2.608 testes / 0 falhas**. `lintRelease`/CI: verdes.

## Mudança

`applyCard` e `undoCardNow` agora usam `db.withTransaction`:

1. `applyCard`: escreve o alvo (`SECTION.contentHtml` ou `NOTE.richHtml`)
   e atualiza o payload para `APPLIED` na **mesma** transação Room. Se a
   atualização do card falhar, o alvo volta ao snapshot (rollback) e o
   catch marca `FAILED` uma única vez.
2. `undoCardNow`: restaura o alvo e atualiza o card para `AVAILABLE` na
   mesma transação. Se o status falhar, o texto não fica restaurado pela
   metade.
3. O snapshot de undo continua em memória e só é criado/removido depois
   do commit; não há escrita parcial visível.
4. Se o processo morrer depois do commit, alvo e `APPLIED` estão os dois
   persistidos; não há retry duplicando.

## Teste de atomicidade no A34

Foi injetado um card `AVAILABLE` apontando para a seção
`c987ef29...` (conteúdo de 5.922 chars) e um trigger SQLite que aborta
**somente** o UPDATE do card para `APPLIED`:

```sql
CREATE TRIGGER fail_card_applied BEFORE UPDATE ON chat_messages
WHEN NEW.id='msg-atomic-insert-0001'
 AND NEW.payload LIKE '%"cardStatus":"APPLIED"%'
BEGIN SELECT RAISE(ABORT, 'atomicity test'); END;
```

Resultado do “Adicionar ao final”:

- a transação falhou no UPDATE do card;
- `contentHtml` da seção continuou **5.922**, com `ATOMIC_TEST = 0`;
- card ficou `FAILED`, sem `cardAppliedAt`;
- UI mostrou “Falha na ação” + “Tentar novamente”.

Depois o trigger foi removido, o app reiniciou e o retry foi executado:

- `contentHtml` foi para **5.941**;
- `ATOMIC_TEST` apareceu **uma única vez**;
- card ficou `APPLIED`, com `cardAppliedAt`;
- nenhuma duplicação.

## Observações

- O banco de teste foi restaurado ao estado anterior ao smoke.
- O teste de falha “depois do write” foi induzido no UPDATE do card, que
  é a segunda escrita da transação; o rollback preservou o alvo.
- A falha “antes do write” é coberta pelo mesmo caminho de catch; o alvo
  não é tocado.
- Tablet/keystore continuam gates da `v2.9.0`.
