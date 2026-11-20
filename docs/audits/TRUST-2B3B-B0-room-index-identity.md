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
  -> (canonicalRef vazio) REFERENCE_MISMATCH
  -> (claim.canonicalRef divergente) METADATA_MISMATCH
PassageDao.findByRefsIn(attachmentId, [canonicalRef])  // sem LIMIT 1
  -> (vazio) RECORD_MISSING
  -> (>1) AMBIGUOUS_RECORD
  -> (attachmentId do registro divergente) ATTACHMENT_MISMATCH
  -> (campos obrigatórios inválidos) METADATA_MISMATCH
  -> (ref divergente ou vazio) REFERENCE_MISMATCH
  -> (claim divergente) METADATA_MISMATCH
  -> (PassageDao.findByTextInAttachment(attachmentId, text, 2) >= 2) AMBIGUOUS_RECORD
  -> (falha de leitura em qualquer consulta) READ_FAILED
  -> RECORD_MATCH + NwtIndexedUnit a partir do registro persistido
```

A identidade de uma passagem é decidida pelo par `attachmentId + canonicalRef`. A presença da mesma referência ou do mesmo texto em outro anexo não invalida automaticamente a identidade do registro do anexo selecionado (P1). A detecção de duplicatas dentro do anexo selecionado é preservada.

O adaptador não usa `findByRef(ref)` global como condição bloqueante para `RECORD_MATCH` (P1). O método `PassageDao.findByTextInAttachment` (novo, sem migração de schema) substitui a consulta limitada a 500 registros de `forAttachment()` para a verificação de ambiguidade de texto (P2).

Falhas de leitura em qualquer consulta necessária à identidade retornam `READ_FAILED` com `ROOM_READ_FAILED` — nunca `RECORD_MATCH` (P3). `CancellationException` é repropagada.

A referência canônica solicitada não pode ser vazia e deve coincidir com `claim.canonicalRef` e com a referência persistida (P4). Uma referência persistida vazia não é substituída silenciosamente pelo valor solicitado. Se o claim estiver ausente, `RECORD_MATCH` comprova apenas a identidade do registro consultado, não a concordância com uma reivindicação externa.

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

- Consulta restrita por `attachmentId` (nunca global para comprovar identidade).
- `findByRefsIn` sem `LIMIT 1` para detectar duplicatas dentro do anexo.
- `findByTextInAttachment` (até 2 registros) para detectar duplicação de texto sem carregar toda a TNM.
- Divergência de `passageId`/`ord`/`section`/`text` → `METADATA_MISMATCH`.
- Registros duplicados (mesmo ref) → `AMBIGUOUS_RECORD`.
- Mesmo texto em múltiplas unidades do mesmo anexo → `AMBIGUOUS_RECORD`.
- Presença da mesma referência/texto em outro anexo **não** invalida a identidade do anexo selecionado (P1).
- Cancelamento de coroutine é repropagado (não engolido).
- Falha de leitura em qualquer consulta necessária → `READ_FAILED` (P3).
- Anexo não TNM / não pronto / ausente → `ATTACHMENT_MISMATCH`/`INDEX_NOT_READY`.
- `canonicalRef` vazio → `REFERENCE_MISMATCH` (P4).
- `claim.canonicalRef` divergente → `METADATA_MISMATCH` (P4).
- Referência persistida vazia não é substituída silenciosamente (P4).

## Testes

34 casos JVM (fakes in-memory), todos verdes:

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
13. `recordMatchEmDuasEdicoesSimultaneas` (P1 — duas edições no mesmo repositório)
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
25. `textoUnicoAposMaisDe500Passagens` (P2)
26. `textoDuplicadoAposPosicao500` (P2)
27. `textoDuplicadoNasPrimeirasPosicoes` (P2)
28. `mesmoTextoEmOutroAnexoNaoInvalidaAnexoSelecionado` (P2/P1)
29. `readFailedQuandoFindByTextInAttachmentFalha` (P3)
30. `rethrowCancellationNaConsultaDeDuplicatas` (P3)
31. `nenhumCaminhoDeFalhaProduzRecordMatch` (P3)
32. `metadataMismatchQuandoClaimComOutraReferencia` (P4)
33. `referenceMismatchQuandoRefSolicitadaVazia` (P4)
34. `referenceMismatchQuandoRefPersistidaVazia` (P4)

## Limitações

- A prova de persistência Room real depende de testes instrumentados (sem dispositivo/emulador neste ambiente). As garantias acima são verificadas por fakes in-memory.
- `findByRef` global tem `LIMIT 1` (Room) — não é mais usado como condição bloqueante para `RECORD_MATCH` (P1). A identidade é decidida por `attachmentId + canonicalRef`.
- `forAttachment` tem `LIMIT 500` — não é mais usado para a verificação de ambiguidade de texto (P2); substituído por `findByTextInAttachment` (até 2 registros, SQL filtrado).
- Leitura inconsistente entre anexo e passagens (atualização concorrente do índice) é mitigada pela validação de `passage.attachmentId == attachmentId` e dos campos obrigatórios; atomicidade cross-tabela não é garantida sem `@Transaction` (limitação documentada, sem mudança de schema).
- Nenhuma rota de produção foi alterada.
- Nenhuma migração de schema Room.
- Nenhuma autenticação oficial da TNM.

## Próxima fase

- Verificação oficial da âncora editorial (trust-2b3b-b1 ou posterior, com política externa).
- Integração do adaptador com o verificador em rotas experimentais.
- Eventual autorização de promoção factual (requer revisão independente e contrato próprio).