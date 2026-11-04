# Spec — Sync (M0: auditoria + escopo MVP)

**Status:** auditoria concluída em 2026-11-02 · **Código:** esqueleto funcional, release desligado
**Decisão:** sync fica **opt-in/experimental** até o E2E de dois aparelhos passar.

## 1. Inventário do que existe hoje

| Componente | Onde | Estado |
|---|---|---|
| `SyncWorker` | `data/work/SyncWorker.kt` (731 linhas) | Notas, seções, subpontos, pastas, anexos (metadados), outlines, tombstones |
| `SyncRemote` / `FirestoreSyncRemote` | `data/work/` | getAll/get/set(merge)/delete + `serverNow()` |
| Conflito | Worker | **LWW por `updatedAt`** (sem merge por campo) |
| Exclusão | Worker + `TombstoneEntity` | tombstones por id e tipo |
| Relógio | Worker | aborta se skew > **5 min** |
| Conta | `AuthRepository` + `SettingsStore.lastUid` | bloqueia troca de conta no aparelho |
| Agendamento | `SyncScheduler` | debounce 2 s + periódico 6 h |
| Anexos | Worker | metadados + `sourceUrl`; **binário nunca sobe** |
| UI | `AccountScreen`, `SyncTopBanner` | login, status, sync now, wipe local/nuvem |
| Rules | `firestore.rules` | baseline uid-scoped (existe; sem validação de schema/testes) |
| Release | `build.gradle` | sem `google-services.json` → sync desligado |

Coleções flat em `users/{uid}/`: `notes`, `folders`, `sections`, `subPoints`,
`attachments`, `outlines`, `tombstones`, `_sync/clock`.

## 2. Auditoria — achados

### F1 (P1) — Reordenar seção/subponto não sincroniza
`moveSection`/`moveSubPoint` renormalizam `order` **sem atualizar `updatedAt`**
(`SectionsController.renormalizeSections`). No push, `sec.updatedAt > cup` é
falso quando o remoto tem o mesmo horário → a nova ordem nunca sobe; no pull,
a ordem antiga pode voltar. Edições de texto não sofrem disso (bumpam updatedAt).

**Correção proposta:** bump de `updatedAt` nos itens cujo `order` mudou
(seções e subpontos) no `renormalize`; teste unitário de reorder + sync.

### F2 (P1) — LWW perde edição concorrente
Dois aparelhos editando a mesma nota (campos diferentes) → o `updatedAt` maior
vence inteiro; o outro perde. Sem merge por campo e sem cópia.

**Decisão recomendada:** LWW + **cópia de conflito por nota**. No pull, se o
local mudou desde o último sync e o remoto também, cria nota
`"<título> (conflito)"` com o estado local antes de aplicar o remoto.
Rastreio: `lastSyncedAt` por nota (novo campo) ou um mapa local
`noteId → updatedAt` do último sync.

### F3 (P1) — Primeiro sync sem escolha
`lastUid == null` → vincula e faz pull+push LWW na mesma execução. Com notas
locais e conta já populada, o resultado é união (ok) + LWW em IDs iguais (F2),
sem aviso. Backup restaurado em outro aparelho pode colidir IDs.

**Decisão recomendada:** no primeiro sync, diálogo **Enviar / Baixar / Mesclar**
(nunca decidir em silêncio). "Mesclar" = união + cópia de conflito.

### F4 (P2) — Troca de conta sem export
O guard bloqueia a troca e a UI oferece "Apagar dados locais", mas **não
oferece exportar backup** na mesma tela (o export existe em Settings → Backup).

**Decisão recomendada:** no diálogo, "Exportar backup" antes do wipe.

### F5 (P2) — Rules baseline, sem schema/testes
Existe `firestore.rules` uid-scoped (bom), mas sem validação de campos/tamanho
e sem testes no Emulator. Risco: doc malformado de um cliente antigo quebra o
pull dos outros.

**Decisão recomendada:** rules v2 com campos obrigatórios por coleção + limite
de tamanho + `schemaVersion`; suíte de testes no Emulator no CI.

### F6 (P2) — Release sem Firebase
Intencional: sem `google-services.json`, `AuthRepository.isConfigured()=false`
e o worker sai com sucesso. Para ligar: projeto Firebase, **dois apps Android**
(`com.bettertalker.app` e `com.bettertalker.app.debug`), SHA-1/256 dos dois,
Google Auth, json no build (CI secret ou arquivo local).

### F7 (P3) — Tombstones sem TTL
Tombstones sobem e ficam para sempre (a remoção local só acontece quando o doc
não existe na nuvem). Crescem com o uso; não há GC.

**Decisão recomendada:** apagar tombstones remotos com `deletedAt` > 90 dias
num job periódico.

### F8 (P3) — Anexos "missing" no 2º aparelho
Intencional (binário não sobe). O pull cria `status="missing"`, `appPath=""` e
`error` orientando reimportar. A UI da Biblioteca precisa deixar isso explícito
(hoje mostra o status, mas não há CTA "baixar da fonte" no card).

### F9 (P3) — `order` colidindo na criação concorrente
Dois aparelhos adicionam seção ao mesmo tempo → ambas podem nascer com o mesmo
`order`. LWW resolve o conteúdo, mas a ordem fica ambígua. Tie-break por
`(order, id)` na leitura resolve sem merge.

### F10 (P3) — Anexo missing com `appPath=""`
No tombstone remoto, `File(cur.appPath).delete()` com path vazio aponta para o
diretório atual; inofensivo, mas convém guardar `appPath.isNotBlank()`.

## 3. Escopo do MVP

| Entidade | Sincroniza? |
|---|---|
| Notas, pastas | ✅ |
| Seções, subpontos (inclui ordem) | ✅ (após F1) |
| Outlines / S-34 | ✅ |
| Metadados de anexos + `sourceUrl` | ✅ |
| Favoritos/pin/lixeira (campos) | ✅ |
| Arquivos de publicações/passages | ❌ (rebaixar no aparelho) |
| Chat do Copilot | ❌ (decisão separada) |
| Configurações/voz/API key | ❌ (segurança) |

## 4. Decisões (defaults recomendados)

1. **Conflito:** LWW + cópia de conflito por nota. (recomendado)
2. **Primeiro sync:** prompt Enviar/Baixar/Mesclar. (recomendado)
3. **Troca de conta:** exportar backup antes do wipe. (recomendado)
4. **Anexos:** só metadados + `sourceUrl`; sem Storage. (recomendado)
5. **Release:** sync opt-in/experimental até E2E. (recomendado)
6. **Firebase:** projeto criado pelo humano; build agent prepara rules/testes.

## 5. Schema v1 (proposta)

- `users/{uid}/_sync/meta`: `{ schemaVersion: 1, updatedAt }`.
- Cada doc carrega `schemaVersion: 1` (inteiro) além dos campos atuais.
- Worker: se `remote.schemaVersion > local` → aborta com mensagem
  "Atualize o app para sincronizar"; se `< local` → migra no pull.
- Campos obrigatórios por coleção validados nas rules (ver §6).

## 6. Rules v2 (rascunho)

```js
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    // validação por coleção (notas)
    match /users/{uid}/notes/{noteId} {
      allow write: if request.auth.uid == uid
        && request.resource.data.title is string
        && request.resource.data.mdText is string
        && request.resource.data.mdText.size() < 1_000_000
        && request.resource.data.updatedAt is int
        && request.resource.data.schemaVersion == 1;
    }
    // análogo para folders/sections/subPoints/attachments/outlines/tombstones
  }
}
```

> A validação fina por coleção deve ser escrita junto com os testes do
> Emulator (M1) para não bloquear pushes legítimos do cliente atual.

## 7. E2E (Emulator, dois aparelhos)

1. Criar nota no A → aparece no B.
2. Editar a mesma nota nos dois offline → reconectar → cópia de conflito no
   lado perdedor; nenhum texto some.
3. Apagar nota no A → some no B; não ressuscita.
4. Reordenar seções no A → ordem chega no B (F1).
5. Anexo no A → B vê "missing" com CTA de reimportar; sem binário na nuvem.
6. Primeiro sync com notas locais + conta populada → prompt e resultado
   conforme a escolha.
7. Troca de conta → export oferecido antes do wipe.
8. Skew de relógio > 5 min → aborta com mensagem.
9. Offline → reconecta → sem duplicatas.

## 8. Marcos

| Marco | Entrega | Critério |
|---|---|---|
| M0 | Este spec + auditoria | ✅ |
| M1 | Firebase + Auth + rules v2 + Emulator | login debug, rules verdes |
| M2 | Notas/pastas + first-sync + conflito + F1 | ✅ parcial: first-sync validado no A34 (dialog Mesclar → 120+ notas, local preservada); conflito/F1 cobertos por testes JVM; falta E2E de 2 aparelhos |
| M3 | Seções/subpontos/outlines | estrutura consistente |
| M4 | Anexos metadados/missing + CTA | 2º aparelho rebaixa |
| M5 | UI conta/status/conflito/wipe/export | fluxo completo |
| M6 | E2E dois aparelhos | 9 casos verdes |
| M7 | Beta opt-in + release | sync não perde dados |

## 9. O que NÃO entra no MVP

- Merge por campo / CRDT.
- Firebase Storage para binários.
- Sync do chat do Copilot.
- Sync de configurações/voz/API key.
- Multi-conta no mesmo aparelho (continua 1 conta/aparelho).

## 10. Status M2 (2026-11-02)

Implementado e testado (JVM + A34):

- **F1**: reorder de seção/subponto bumpa `updatedAt` (`SectionsController`); 2 testes.
- **Conflito**: `NoteEntity.lastSyncedAt` (migração 20→21); quando local e remoto
  mudaram desde o último sync, cria nota `"<título> (conflito)"` com cópia
  profunda (seções/subpontos) e aplica o remoto no original. 6 testes.
- **First sync**: `syncFirstPending` + `firstSyncMode` (upload/download/merge).
  No A34: app limpo com 1 nota → login → diálogo "Primeiro sync" →
  **Mesclar** → 120+ notas da conta puxadas, nota local preservada.
- **Baixar**: `pushEnabled=false` em todas as coleções; 3 testes.
- **Export antes do wipe**: botão "Exportar backup" no diálogo de troca de conta.

E2E com dois aparelhos (A34 + Redmi 9) em 2026-11-02:

| Caso | Resultado |
|---|---|
| Pull inicial (120+ notas) | ✅ |
| Criar nota no A34 → nuvem → Redmi | ✅ |
| Conflito: mesmo note ID editado nos dois | ✅ cópia "(conflito)" criada dos dois lados (symmetric) |
| Exclusão definitiva (tombstone) | ✅ sumiu no Redmi após sync |
| First-sync prompt | ✅ validado no A34 (Mesclar) |
| Reorder entre devices | ⏳ coberto por teste JVM do F1; não exercitado na UI (sem nota multi-seção prática) |

Bug encontrado e corrigido no E2E: **Credential Manager exige Activity context**
no Android 12; o app passava o application context e o seletor não abria
("failed to launch the selector ui"). `AuthRepository.signIn(uiContext)` agora
recebe o contexto da Activity.

Também corrigido: o conflito era assimétrico (só preservava o lado local quando
o remoto era mais novo). Agora, quando o local vence o LWW, o estado remoto é
preservado numa cópia "(conflito)" antes do push.

## 11. Status M3 (2026-11-03)

O gap real era de **mapeamento**, não de estrutura:

- `notes.discourseType` agora sobe (`noteMap`) e desce (`cloudNote`), com
  fallback para `S34_DISCOURSE` em valores remotos desconhecidos.
- `sections.tribunaJson` agora sobe (`sectionMap`) e desce (`cloudSection`).
- **Backfill push**: docs já sincronizados antes do campo existir têm
  `updatedAt` empatado, então o LWW não os reenviaria. O sync reenvia o doc
  uma vez quando a revisão empata e o campo remoto está ausente/inválido;
  remoto mais novo nunca é sobrescrito.
- **Merge no pull**: com a revisão empatada, o segundo aparelho completa o
  que falta — nota em `S34_DISCOURSE` adota `discourseType` remoto válido e
  seção com `tribunaJson = null` adota a cápsula remota. Nada bumpa
  `updatedAt`.
- **Divergência na mesma revisão**: se os dois lados têm valores diferentes,
  o **local vence** (caso raro; documentado em teste). Valor remoto inválido
  é tratado como ausente para permitir o backfill corretivo.
- `S34_DISCOURSE` (default) **não** é backfillado no push: ausência já
  significa S34. Escrever o default antes bloquearia o valor real de outro
  aparelho, porque o campo passaria a existir e o pull no empate não o
  trocaria.
- Cobertura JVM: push, pull, adoção no empate, fallback, divergência e
  backfill dos dois campos. Sem mudança de dependências Firebase; a
  validação usa o seam `SyncRemote` fake.

## 12. Status M4 (2026-11-03)

Anexos continuam **sem binário na nuvem**; o 2º aparelho recebe só metadados
e o status `missing`.

- `sourceType` e `symbol` agora sobem no doc de anexo novo e descem para o
  aparelho que não tem o arquivo, para a Biblioteca organizar o acervo.
- Docs antigos são completados nos dois sentidos: o pull adota metadados
  remotos quando o local está no default (`content`/`symbol = null`) e o push
  reenvia a classificação local não-default quando o remoto está ausente ou
  default.
- Regra sem `updatedAt` em anexos: classificação local não-default vence o
  default remoto; símbolo local não-nulo vence `null`. Divergência entre dois
  valores não-default mantém o local (caso raro).
- CTA de `missing`: com `sourceUrl`, `Importar da fonte`; sem URL, o novo
  `Escolher arquivo` restaura o **mesmo id** sincronizado (preserva vínculo
  com a nota) e reindexa. O binário anterior não é apagado pelo sync.
- Validado no A34: linha `missing` → `Escolher arquivo` → `Indexado`, mesmo
  id, hash preenchido e arquivo em `files/publicacoes`.
- Cobertura JVM: 4 testes novos no `SyncAttachmentsRemoteTest` (round-trip,
  adoção no aparelho B, backfill de doc antigo e atualização sobre default).

## 13. Status M5 (2026-11-03)

A tela `Conta e nuvem` cobre o fluxo completo:

- login Google, status do sync, último sync, sincronizar agora e sair;
- primeiro sync com escolha Enviar/Baixar/Mesclar;
- conflitos: lista das cópias `(conflito)` com abertura direta no editor e
  “Ver todos” quando passa de 5;
- dados do aparelho: exportar backup e **apagar dados locais** com
  confirmação (a conta continua conectada; o próximo sync pode repopular);
- zona de perigo da conta: apagar dados da nuvem, separada do wipe local;
- troca de conta bloqueada, com exportação antes do wipe e re-login;
- tela rolável em telas pequenas e mensagem de sucesso separada de erro.

Cobertura JVM: `SyncConflictsTest` valida o reconhecimento do sufixo de
conflito (case-insensitive). O restante é UI/fluxo de conta, sem Firebase no
build de release.

## 14. Status M6 (2026-11-03, parcial)

Validação A34 (conta existente) + tablet (25040RP0AL, login novo):

| Caso | Resultado |
|---|---|
| Tablet com tela reduzida: conta rola até o fim | ✅ |
| Login + first-sync “Baixar” no tablet | ✅ 47 notas locais após pull |
| Pull de `discourseType` (TREASURES_TALK/FREE_DEMO) | ✅ |
| Pull de `tribunaJson` + Palco “Roteiro pronto”/“Apresentar” | ✅ |
| Palco live com turnos Publicador/Morador | ✅ |
| Anexo com `sourceUrl` no tablet | ✅ `Importar da fonte` |
| Anexo sem `sourceUrl` no tablet | ✅ `Escolher arquivo` |
| `sourceType`/`symbol` de anexos no tablet | ✅ (bible/nwt, training/be…) |
| Wipe local: diálogo + Exportar + Cancelar | ✅ (sem confirmar) |
| Conflito real (tablet perde o LWW) | ✅ cópia `(conflito)` com FREE_DEMO + `tribunaJson` |
| Conflito inverso (A34 perde o LWW) | ✅ cópia `(conflito)` com FREE_DEMO + `tribunaJson` |
| Conta → Conflitos → “Abrir” | ✅ abriu o editor da cópia |
| Instalação do APK de teste no tablet | ✅ (autorizada; “Instalar via USB” ligado) |
| Tombstone entre A34/tablet | ✅ notas removidas nos dois após tombstones |
| Reorder de 3 seções A34 → tablet | ✅ ordem 2/0/1 propagou |
| Offline: nota criada em cada aparelho sem sync | ✅ união A+B, sem duplicatas |
| Wipe local confirmado no tablet | ✅ 0 notas → sync → 47 notas |

Bug real encontrado no reorder: o pull aplicava as seções uma a uma e
violava o índice único `(noteId, order)` no segundo aparelho. O sync agora
coleta o pull e aplica em duas fases (ordens temporárias → ordem remota),
dentro de `db.withTransaction`; ordens negativas de pull interrompido são
normalizadas no início do próximo sync. Orders remotos duplicados são
normalizados com tie-break `(order, id)`. Testes JVM cobrem os três casos.
Política documentada: quando a ordem remota vence, seções locais que não
existem no remoto vão para o fim, preservando o conteúdo.

Pendentes: wipe de nuvem e troca de conta (gates do M7, sem conta de teste),
skew no Redmi e reexecução do M6 no par A34 + Redmi quando ele voltar ao ADB.
As fixtures foram removidas nos dois aparelhos e os tombstones sincronizados.

