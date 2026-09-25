# Release Candidate v1.0 — Better-Talker (documentação §31)

Avaliação em cima do commit `64c26bf` + correções RC (ver commit final).

## Veredito

```text
READY_FOR_V1
```

Condicionado às pendências manuais listadas abaixo (nenhum bloqueador
automatizável aberto).

## Ambiente

- Commit base: `64c26bf` (main), mais correções RC no commit final.
- Node 24, npm 11, Vite 8, vitest 5; Chrome 154 headless; Java 21,
  Gradle 8.14.3, AGP 8.13.0, Kotlin 2.3.21, Room 2.8.5.
- Trabalho paralelo de terceiros em `android/` preservado e excluído
  dos commits (hunks cirúrgicos quando necessário).

## Resultados

- Web tests: **165 + 4 = 169/169** (`npx vitest run`).
- Web build: verde (`npm run build`, `dist/`).
- Web lint: 0 erros, 6 warnings pré-existentes.
- Android tests: **160/160** (`:app:testDebugUnitTest`).
- Android assemble: verde (`:app:assembleDebug`).
- Android lint: 2 erros pré-existentes em arquivos intocados
  (`activity_main.xml` MissingClass, `JwDownloadHost.kt` ContextCast);
  0 erros/warnings novos da RC.
- E2E lógico F9→F5→F6: verde (flow.test.ts).
- Web real (Chrome 154, build `dist/` servido): **boot completo, editor
  renderizado, discurso demo carregado** (via CDP, com espera real).
- Persistência: Dexie com dados reais intactos no profile; fallback
  localStorage validado (seed + render sem IndexedDB).
- Offline: caminhos locais sem `fetch`; remoto ausente = `ProviderError`.
- Providers: 5xx/400/401/429/timeout/cancel cobertos; sem texto fictício.
- Importação: `(N min)`, vazio, binário (degrada); `.docx` real segue
  limitação documentada (POST-v1.0).
- Proveniência: 10 campos ponta a ponta; ausente = null.
- Cache: hit/miss/invalidação/escopo/cap-50 com eviction.
- Concorrência/edição: stale, redo invalidado, atomicidade, limites.
- UI: loading/success/empty/error/offline/stale/insufficient existem;
  botões com títulos; glyphs sempre com texto.
- Performance: retrieval 600 = 13ms; análise 150 blocos = 98ms;
  verificação 20 claims = 3ms.
- Segurança: sem chaves no código; `.env*` ignorado; sync só metadados
  com guarda anti-binário; offline não faz request.

## Correções RC (única mudança funcional)

**Travamento infinito na abertura**: com IndexedDB pendurado, o app
ficava em "Carregando" para sempre (provado em headless: `IDB-HANG`).
Correção: `withStorageTimeout` (8s) em todas as operações Dexie +
corrida de 10s no `initFirebaseSync`. Fallbacks locais existentes assumem.
Comportamento normal inalterado (só dispara em hang real).
Prova: boot completo em Chrome real com IndexedDB inoperante.

## Pendências manuais (não bloqueadoras)

1. Ciclo manual completo no navegador (criar→editar→F9→F5→F6→persistir).
2. Aparelho/emulador real (existe AVD `Small_Phone`; não executado aqui).
3. `MigrationTestHelper` instrumentado (SQL validado em SQLite real).
4. Persistência Room em aparelho (schema validado em compilação).

## Riscos e dívidas pós-v1.0

Semântica real; DOCX; WPM na UI de análise; analyzer Kotlin; diff por
palavra; track paralelo nos mesmos arquivos Android.

## Artefatos (não commitados, ignorados pelo git)

- `dist/` (web): `index.html` 1813 bytes (build completo em `dist/assets/`).
- `app-debug.apk` (build limpo do HEAD `64c26bf`, sem trabalho paralelo):
  `app/build/outputs/apk/debug/app-debug.apk`, 139M,
  sha256 `530d7d89694ea40e72700cc24c831fa8b7a933c0155c9d3c3d37a701ed28ea32`.
