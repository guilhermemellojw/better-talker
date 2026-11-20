# TRUST-2B3B-B0 — Adaptador experimental de identidade do índice Room

## Objetivo

Construir uma integração experimental que responda:

> "Os metadados desta unidade bíblica correspondem realmente a um registro persistido no índice local da TNM?"

O adaptador `NwtRoomIndexIdentityAdapter` consulta o Room (AttachmentDao/PassageDao) restrito a um `attachmentId` específico e compara o registro real com a unidade reivindicada. Constrói `NwtIndexedUnit` exclusivamente a partir dos dados persistidos e somente quando a identidade é comprovada.

O componente é experimental, puro (sem UI, Room migration, LLM ou rede), não conectado a produção e sem promoção factual.

## Entrada

- `attachmentId`: identificador específico do anexo (obrigatório, não vazio).
- `canonicalRef`: referência canônica a localizar no índice.
- `claim`: `NwtIndexedUnit?` reivindicado pelo chamador (opcional; se presente, é comparado com o registro persistido, nunca considerado fonte de verdade).
- `fileSha256`: hash SHA-256 atual do arquivo (opcional; se fornecido, comparado com `AttachmentEntity.sha256`).
- `attachmentDao`, `passageDao`: DAOs Room.

## Saída

`NwtRoomIndexIdentityResult`:

| Campo | Significado |
|---|---|
| `state` | `RECORD_MATCH`, `RECORD_MISSING`, `ATTACHMENT_MISMATCH`, `REFERENCE_MISMATCH`, `METADATA_MISMATCH`, `AMBIGUOUS_RECORD`, `INDEX_NOT_READY`, `READ_FAILED` |
| `attachment` | entidade persistida do anexo (ou null) |
| `passage` | entidade persistida da passagem (ou null) |
| `recordCount` | número de registros persistidos para o ref |
| `unit` | `NwtIndexedUnit` construído a partir do registro persistido, somente em `RECORD_MATCH` |
| `issues` | diagnósticos explícitos |

## Estados

- **RECORD_MATCH:** registro persistido coincide com a unidade reivindicada (anexo TNM pronto, único registro para o ref, metadados consistentes, sem ambiguidade).
- **RECORD_MISSING:** nenhum registro persistido para o anexo/referência.
- **ATTACHMENT_MISMATCH:** anexo ausente, não TNM, incompatível ou claim de outro anexo.
- **REFERENCE_MISMATCH:** referência canônica diverge do registro persistido.
- **METADATA_MISMATCH:** `passageId`/`ord`/`section`/`text` divergem do registro persistido, ou hash do arquivo difere do registrado.
- **AMBIGUOUS_RECORD:** registros duplicados ou ambíguos (mesmo ref/texto em múltiplas linhas, ou mesmo ref em outro anexo).
- **INDEX_NOT_READY:** anexo não indexado ou status != ready.
- **READ_FAILED:** falha ao consultar o Room (CancellationException é repropagada, não engolida).

## Fluxo

```
AttachmentDao.get(attachmentId)
  -> (null) ATTACHMENT_MISMATCH
  -> (não indexado/status != ready) INDEX_NOT_READY
  -> (não TNM) ATTACHMENT_MISMATCH
  -> (fileSha256 fornecido e divergente) METADATA_MISMATCH
  -> (claim.attachmentId divergente) ATTACHMENT_MISMATCH
PassageDao.findByRefsIn(attachmentId, [canonicalRef])  // sem LIMIT 1
  -> (vazio) RECORD_MISSING
  -> (>1) AMBIGUOUS_RECORD
  -> (ref divergente) REFERENCE_MISMATCH
  -> (claim divergente) METADATA_MISMATCH
  -> (findByRef global em outro anexo) AMBIGUOUS_RECORD
  -> (mesmo texto em >1 unidade do anexo) AMBIGUOUS_RECORD
  -> RECORD_MATCH + NwtIndexedUnit a partir do registro persistido
```

O adaptador nunca usa `findByRef(ref)` global para comprovar identidade (apenas como verificação best-effort de ambiguidade entre anexos).

## Integração com o verificador existente

```
AttachmentDao → PassageDao → NwtIndexedUnit → NwtDocumentIdentityVerifier
```

O adaptador constrói `NwtIndexedUnit` somente a partir do registro persistido. O verificador documental existente examina essa unidade contra o EPUB (integridade, XHTML, estrutura, texto).

## Preservação das camadas de confiança

Três verificações permanecem separadas:

1. **Integridade documental** — o arquivo corresponde ao hash registrado? (verificador, `HASH_MATCH`).
2. **Identidade do índice** — a unidade consultada corresponde ao registro realmente persistido? (este adaptador, `RECORD_MATCH`).
3. **Autenticidade editorial** — há evidência independente de que o arquivo é uma edição oficial? (fora do escopo deste B0).

## Política de bloqueio obrigatório

Mesmo com `RECORD_MATCH`, os campos globais de promoção do TRUST-2B3B-A permanecem `false`:

- `anchorProvenanceTrusted = false`
- `unitIndexIdentityVerified = false`
- `canPromoteUnitToLocalFactual = false`
- `canPromoteCorpusToLocalFactual = false`

O adaptador apresenta separadamente um resultado positivo de identidade do registro persistido, mas não altera os campos globais de promoção. Nenhum conteúdo é promovido a `VERIFIED_LOCAL`.

## Segurança contra ambiguidades

- Consulta restrita por `attachmentId` (nunca global).
- `findByRefsIn` sem `LIMIT 1` para detectar duplicatas.
- Divergência de `passageId`/`ord`/`section`/`text` → `METADATA_MISMATCH`.
- Registros duplicados → `AMBIGUOUS_RECORD`.
- Mesmo texto em múltiplas unidades → `AMBIGUOUS_RECORD`.
- Mesmo ref em outro anexo → `AMBIGUOUS_RECORD` (best-effort via `findByRef`).
- Cancelamento de coroutine é repropagado (não engolido).
- Falha de leitura do Room → `READ_FAILED`.
- Anexo não TNM / não pronto / ausente → `ATTACHMENT_MISMATCH`/`INDEX_NOT_READY`.

## Testes

24 casos JVM (fakes in-memory), todos verdes:

1. `recordMatchQuandoPassagemPertenceAoAnexoCorreto`
2. `recordMissingQuandoPassagemPertenceAOutroAnexo`
3. `metadataMismatchQuandoPassageIdFabricado`
4. `metadataMismatchQuandoOrdIncorreto`
5. `metadataMismatchQuandoSectionIncorreta`
6. `referenceMismatchQuandoRefIncompativel`
7. `metadataMismatchQuandoTextoAlterado`
8. `recordMissingQuandoPassagemAusente`
9. `attachmentMismatchQuandoAnexoRemovido`
10. `indexNotReadyQuandoAnexoNaoIndexado`
11. `recordMatchEmDuasEdicoesIndependentes`
12. `ambiguousRecordQuandoMesmoTextoEmDuasUnidades`
13. `ambiguousRecordQuandoMesmoTextoEmDoisAnexos`
14. `ambiguousRecordQuandoRegistrosDuplicados`
15. `metadataMismatchQuandoArquivoIncompativelComIndice`
16. `readFailedQuandoConsultaRoomFalha`
17. `rethrowCancellationException`
18. `adapterUnitAlimentaVerificadorComMismatchDeEpub` (cadeia adapter → verificador)
19. `attachmentMismatchQuandoConsultaSemAttachmentId`
20. `attachmentMismatchQuandoAnexoNaoTnm`
21. `recordMatchConstróiUnitAPartirDoPersistido`
22. `claimNullAindaVerificaRecordMatch`
23. `metadataMismatchQuandoClaimAttachmentIdDiverge`
24. `readFailedQuandoFindByRefsInFalha`

## Limitações

- A prova de persistência Room real depende de testes instrumentados (sem dispositivo/emulador neste ambiente). As garantias acima são verificadas por fakes in-memory.
- `findByRef` global tem `LIMIT 1` (Room) — a verificação de ambiguidade entre anexos é best-effort.
- `forAttachment` tem `LIMIT 500` — a verificação de ambiguidade de texto é parcial para anexos muito grandes.
- Nenhuma rota de produção foi alterada.
- Nenhuma migração de schema Room.
- Nenhuma autenticação oficial da TNM.

## Próxima fase

- Verificação oficial da âncora editorial (trust-2b3b-b1 ou posterior, com política externa).
- Integração do adaptador com o verificador em rotas experimentais.
- Eventual autorização de promoção factual (requer revisão independente e contrato próprio).