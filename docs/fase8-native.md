# Fase 8 — Base nativa Kotlin/Compose/Room (documentação §22)

## 1. Arquitetura Android

```text
Compose (ChatScreen, ChatCards, EditorScreen, LibraryScreen)
  ↓ StateFlow (sem acesso a Room/DAO no Compose)
ViewModel (CopilotViewModel, EditorViewModel, ...)
  ↓
Repository — interfaces de domínio (NOVO: RetrievalRepository,
  TrainingRepository, PublicationRepository, SpeechRepository,
  ContextPackRepository) + legados concretos (notas, biblioteca, chat)
  ↓
Room DAO (PassageDao.forAttachments, AttachmentDao, ...) / SQLite
```

Retrieval/contexto:

```text
ViewModel/use case
  ↓
RoomRetrievalRepository / RoomTrainingRepository
  ↓  carga restrita ao escopo (forAttachments)
HybridRetrieval (puro): TF-IDF + metadata + rerank + boost de categoria
  ↓  ContentPack (trilhos separados)
```

Domínio (`data/domain/`, puro JVM) não depende de Room.

## 2. Módulos

Monólito `:app`. Sem Capacitor (100% nativo Compose — sem bridge web↔nativo;
web e nativo evoluem em paralelo com os mesmos contratos).

## 3. Room schema — v10

Novas colunas (todas aditivas; proveniência ausente = null, nunca inferida):

- `attachments`: `sourceType TEXT NOT NULL DEFAULT 'content'`,
  `symbol TEXT`; índice `index_attachments_baseSlot`.
- `passages`: `ref TEXT NOT NULL DEFAULT ''`, `page INTEGER`,
  `paragraph INTEGER`, `ord INTEGER NOT NULL DEFAULT 0`,
  `trainingCategory TEXT`; índice `index_passages_attachmentId`.
- DAO novo: `passageDao.forAttachments(ids)` (ordenado p/ determinismo);
  `attachmentDao.setSourceMeta(id, sourceType, symbol)`.

## 4. Migrations

`MIGRATION_9_10` explícita: ADD COLUMNs + CREATE INDEX + backfill
(`baseSlot be|th → training`, `nwt → bible`, demais `content`).
Teste de execução em JVM indisponível (Room exige Android): sem
`room-testing` no cache offline; validado por compilação kapt (schema +
queries) + assembleDebug. Limitação honesta, sem falsa migration.

## 5. Repositories

Interfaces novas + impls `Room*` recebendo DAOs (testáveis com fakes;
coroutines via `suspend`, sem bloqueio da UI). Legados concretos intocados.
`IndexPublicationWorker` preenche `ord`, `trainingCategory` (só training,
via `TrainingClassifier`) e persiste `sourceType`/`symbol` do anexo.

## 6. Domínio

`TrainingCategory` (enum, serial minúsculo estável, `UNKNOWN` fallback),
`SourceType` (CONTENT/TRAINING/BIBLE, `fromBaseSlot` p/ legado),
`Speech/SpeechBlock` (Nota+Outline adaptados), `Publication`, `Passage`,
`EvidenceSource`, `RetrievalScope/Result/Candidate/Status`, `ContextPack`.
Adapters Entity→Domain sem inferência.

## 7. CONTENT/TRAINING

Trilho por `sourceType` (explícito, com fallback legado `baseSlot`).
Factual => só content (`contentOnly`, trilho training vazio por construção).
`partitionGuideHits` legado preservado e testado.

## 8. TrainingCategory

Taxonomia Fase 7 (11+unknown). Classificador determinístico com as mesmas
regras da web (seção/título > corpo; padrões sem acento). Categoria gravada
no índice prevalece; registros antigos ganham categoria on-the-fly.

## 9. Retrieval

`HybridRetrieval.rank`: TF-IDF (df=0 contribui 0, como na web) + bônus de
frase exata + metadata (símbolo 0.45/título 0.2/seção 0.2/ref 0.15) +
pesos 0.4/0.2/0.3/0.1 + desempate determinístico. Escopo vazio ou query
vazia => `INSUFFICIENT_SCOPE` (**fallback global do `askScoped` removido**;
chamadas do chat já tratavam lista vazia). `applyCategoryBoost` (+0.15,
sem tocar no score).

## 10. ContextPack

`ContextPacks.fromCandidates(content, training)` com limites 8/4;
`contentOnly()` para factual (§20). Training carrega `training_category`.

## 11. Provenance

passageId, pubId, publication, title, section, symbol, page, paragraph,
ref, score, matchedTerms, foundBy, trainingCategory. UI (`ProvenanceLine`):
📖 conteúdo / 🎤 técnica (categoria). `IdeaCard.trainingCategory`
persistido no codec com grupo opcional (mensagens antigas => null).

## 12. Offline-first

Tudo local: Room, retrieval em memória sobre o escopo, classifier puro.
Nenhuma chamada de rede no caminho. Firebase intocado (só metadados,
como antes).

## 13. Limitações

1. Sem migration test executável (sem Robolectric/instrumentado aqui).
2. Sem teste Room real (fakes de DAO cobrem a lógica; Room é validado em build).
3. `SpeechRepository` implementado, mas editor nativo segue em notas (adapter documentado, sem migração de UX).
4. Semântica real inexistente (peso reservado, como na web).
5. Lint Android: 2 erros + warnings pré-existentes em arquivos intocados.

## 14. Diferenças inevitáveis web x nativo

- Web: Speech/blocks de primeira classe; nativo: Nota+Outline adaptados.
- Web: Dexie; nativo: Room (projeção equivalente, não idêntica).
- Web: `askScoped` não existe; nativo mantém `askScoped` (chat) sem fallback.
- `ExampleKind` (4) do chat preservado; `TrainingCategory` (12) é a taxonomia nova.

## 15. Preparação para F9

Trilhos, categorias, ContextPack e proveniência prontos para análise
estrutural (introdução/conclusão/transições) sem mudar o schema.
