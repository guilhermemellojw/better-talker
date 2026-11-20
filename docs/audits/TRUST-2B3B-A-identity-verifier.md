# TRUST-2B3B-A — Verificador experimental de identidade documental da TNM

## Objetivo

Fornecer um contrato puro, determinístico e isolado para verificar o que as
evidências de um EPUB local realmente comprovam:

- integridade em relação ao SHA-256 registrado na importação;
- consistência dos metadados editoriais internos (OPF);
- correspondência entre a unidade indexada e o texto extraído do arquivo;
- completude **relativa ao próprio EPUB**, nunca em relação a outra tradução.

O componente **não** está conectado ao Mentor, RAG, prompts, SourceGate,
GroundednessVerifier, CopilotViewModel ou RoomReferenceResolver. Nenhuma
migração Room é necessária.

**Neste BUILD, nenhum resultado é promovido a uso factual.** A promoção
depende de infraestrutura externa de confiança na âncora e de consulta ao
índice persistido, que ainda não existem.

## Entrada

`NwtDocumentIdentityVerifier.verify(...)` recebe:

- `NwtAttachmentMetadata`: `attachmentId`, nome, kind, baseSlot, symbol,
  indexed, status, SHA-256 registrado, tamanho e caminho;
- `File`: o EPUB atual;
- `NwtIndexedUnit?`: `attachmentId`, `passageId`, referência canônica, seção,
  `ord` e texto indexado;
- `NwtExpectedEdition`: perfil de consistência editorial e, opcionalmente,
  uma âncora externa para **correspondência**;
- `NwtVerifierLimits`: tetos de XML, XHTML, entradas e tamanho descomprimido.

## Saída

`NwtDocumentIdentity` contém:

| Campo | Significado |
|---|---|
| `integrity` | `FILE_MISSING`, `FILE_UNREADABLE`, `NOT_EPUB`, `HASH_MISSING`, `HASH_MISMATCH`, `HASH_MATCH` |
| `editorialState` | `OPF_MISSING`, `OPF_INVALID`, `METADATA_INCONSISTENT`, `IDENTITY_UNVERIFIABLE`, `IDENTITY_CONSISTENT` |
| `authenticity` | `NOT_ANCHORED`, `ANCHOR_MATCH`, `ANCHOR_MISMATCH` — apenas correspondência com a âncora fornecida |
| `unit.state` | `UNIT_MISSING`, `UNIT_PARTIAL`, `UNIT_TEXT_VERIFIED`, `INDEX_STALE`, `UNIT_UNVERIFIABLE` |
| `unit.comparison` | `EXACT`, `NORMALIZED_WHITESPACE`, `MISMATCH`, `NOT_COMPARED` |
| `unit.textMatchesEpub` | fidelidade textual ao EPUB |
| `unit.metadataShapeComplete` | `passageId`, `ord` e `section` presentes |
| `unit.sectionMatchesEpub` | `null` = não fornecida; `false` = diverge |
| `unit.indexIdentityVerified` | sempre `false` neste BUILD |
| `corpus.state` | `COMPLETE_RELATIVE_TO_EPUB`, `PARTIAL`, `INCONSISTENT`, `UNKNOWN` |
| `promotion` | diagnóstico de elegibilidade; promoção factual sempre bloqueada |

## O que cada estado comprova

### `IDENTITY_CONSISTENT`

Comprova que o OPF contém título, idioma, editora e identificador presentes e
compatíveis com o perfil esperado. **Não comprova** que a publicação é oficial,
que o arquivo foi obtido legitimamente ou que a âncora fornecida é confiável.

### `ANCHOR_MATCH`

Comprova apenas que o identificador e/ou hash do arquivo coincidem com a âncora
fornecida pelo chamador. Um UUID pode ser copiado e um hash pode ser calculado
sobre um arquivo adulterado. Correspondência não é autorização.

### `UNIT_TEXT_VERIFIED`

Comprova que o texto indexado coincide com o texto extraído do EPUB (exato ou
com normalização estrita de espaços). **Não comprova** que `passageId`, `ord` ou
`section` pertencem ao registro persistido. `indexIdentityVerified` permanece
`false` até existir um adaptador que consulte o índice real.

### `COMPLETE_RELATIVE_TO_EPUB`

Comprova que container, manifesto, spine, IDs e todos os recursos XHTML foram
estruturalmente validados, que os recursos de spine relevantes foram lidos e
processados, que os marcadores de versículo coincidem com o parser e que os 66
livros foram identificados. IDs duplicados/vazios, `itemref` inválido ou XHTML
malformado impedem esse estado. **Não comprova** completude canônica nem que a
edição é oficial.

## Riscos investigados

### R1 — Âncora editorial insuficiente

- Confirmado: `ANCHOR_MATCH` podia ser obtido apenas com identificador.
- Correção: a correspondência continua sendo reportada, mas
  `anchorProvenanceTrusted=false` e `canPromoteUnitToLocalFactual=false` em
  todos os casos. Um blocker `ANCHOR_PROVENANCE_UNVERIFIED` é sempre emitido.
- Teste permanente: `ancoraApenasComIdentificadorNaoPromove`.

### R2 — Identidade da unidade não comprovada

- Confirmado: texto correto podia coexistir com `passageId`/`ord`/`section`
  arbitrários e ainda habilitar promoção.
- Correção: `UNIT_TEXT_VERIFIED` separa fidelidade textual de identidade do
  índice; `indexIdentityVerified=false`; seção divergente vira `UNIT_PARTIAL`;
  texto repetido em outra unidade vira `UNIT_PARTIAL`.
- Testes: `passageIdFabricadoNaoEhComprovadoPeloEpub`,
  `ordIncorretoNaoEhComprovadoPeloEpub`, `secaoDeOutraUnidadeViraPartial`,
  `mesmoTextoEmUnidadesDiferentesViraPartial`.

### R3 — Completude com falhas de leitura

- Confirmado: recursos de spine ausentes/ilegíveis podiam ser ignorados e o
  corpus ainda retornar `COMPLETE_RELATIVE_TO_EPUB` se os 66 livros e os
  marcadores restantes coincidissem.
- Correção: `failedResources > 0` impede `COMPLETE`; o estado vira `PARTIAL`
  ou `UNKNOWN`. O resultado expõe `processedResources` e `failedResources`.
- Testes: `spineResourceAusenteNaoViraComplete`,
  `spineResourceAcimaDoLimiteNaoViraComplete`,
  `spineBookMalformadoNaoViraComplete`.

#### R3-A — XHTML malformado sem `docId`

- Confirmado: um XHTML não fechado era lido como texto e, sem `docId`, era
  contabilizado como capa/navegação legítima.
- Correção: todo XHTML de spine é validado sintaticamente com o parser XML
  seguro. XHTML inválido gera `XHTML_INVALID`, entra em `failedPaths` e impede
  `COMPLETE_RELATIVE_TO_EPUB`.
- Recursos XHTML não bíblicos sintaticamente válidos continuam aceitos.
- Entidades internas são rejeitadas; DTDs externos e entidades externas não são
  carregados; `DOCTYPE` XHTML comum é aceito.
- Testes: `xhtmlMalformadoSemDocIdImpedeComplete`,
  `xhtmlNaoBiblicoValidoContinuaAceito` (coberto por
  `recursoNaoBiblicoLegitimoEhAceito`).

#### R3-B — IDs duplicados ou vazios no manifesto

- Confirmado: IDs duplicados sobrescreviam entradas no mapa do manifesto.
- Correção: IDs vazios geram `MANIFEST_ID_MISSING`; IDs duplicados geram
  `MANIFEST_ID_DUPLICATE`, mesmo com `href` igual. Itens ambíguos não entram no
  mapa; a estrutura fica inconsistente, a unidade `UNIT_UNVERIFIABLE` e o
  corpus `UNKNOWN`.
- Testes: `manifestoComMesmoIdEHrefIgualContinuaInvalido`,
  `manifestoComIdsDuplicadosHrefDiferenteImpedeComplete`,
  `manifestoComIdVazioNaoEhIgnorado`,
  `falhaEstruturalApareceNosDiagnosticosDoCorpus`.

#### R3 residual — manifesto e spine inconsistentes

- Confirmado: `itemref` com `idref` inexistente era ignorado silenciosamente.
- Correção: cada `itemref` é validado contra o manifesto. São inconsistentes:
  - `idref` vazio;
  - `idref` inexistente;
  - item de manifesto sem `href`;
  - caminho relativo inválido/inseguro;
  - item de spine que não aponta para documento XHTML;
  - item de spine com extensão binária (imagem/fonte/áudio etc.).
- Uma referência inválida torna `structureValid=false`; a unidade fica
  `UNIT_UNVERIFIABLE` e o corpus não retorna `COMPLETE_RELATIVE_TO_EPUB`.
- Recursos XHTML legítimos não bíblicos (capa/navegação) são lidos e contam
  como recursos processados; não precisam ser capítulos bíblicos.
- Testes: `spineIdrefInexistenteImpedeComplete`,
  `spineIdrefVazioImpedeComplete`, `manifestoComItemSemHrefImpedeComplete`,
  `spineComCaminhoInseguroImpedeComplete`, `spineComRecursoBinarioImpedeComplete`,
  `recursoNaoBiblicoLegitimoEhAceito`.

### R4 — Entradas ZIP duplicadas

- Confirmado: duplicatas de XHTML podiam escapar da invalidação estrutural e
  permitir leitura ambígua por `ZipFile.getEntry()`.
- Correção: qualquer nome duplicado no ZIP torna `structureValid=false`; a
  unidade fica `UNIT_UNVERIFIABLE`, o corpus não vira `COMPLETE` e a promoção
  permanece bloqueada. Um teste usa um ZIP bruto com entradas duplicadas.
- Teste: `zipComXhtmlDuplicadoNaoVerificaUnidade`.

## Diagnósticos de promoção

- `NwtPromotionEligibility.blockers` sempre inclui
  `ANCHOR_PROVENANCE_UNVERIFIED` e `INDEX_IDENTITY_UNVERIFIED`, inclusive em
  retornos antecipados como arquivo ausente.
- Em retornos antecipados, o `issues` de topo contém a causa específica
  (`FILE_MISSING`, `HASH_MISSING`, etc.); os dois bloqueadores globais ficam em
  `promotion.blockers`, sem exigir verificações impossíveis.
- `canPromoteUnitToLocalFactual` e `canPromoteCorpusToLocalFactual` permanecem
  `false` em todos os caminhos.

## Segurança

- `ZipFile` com limite de entradas e tamanho descomprimido;
- leitura limitada de `container.xml`, OPF e XHTML;
- XML/OPF com `FEATURE_SECURE_PROCESSING`, DOCTYPE desabilitado, entidades
  externas desabilitadas e `EntityResolver` vazio;
- XHTML validado como XML: `DOCTYPE` comum é aceito, mas entidades internas
  (`<!ENTITY`) são rejeitadas, DTDs externos não são carregados e entidades
  externas permanecem desabilitadas;
- IDs de manifesto vazios ou duplicados invalidam a estrutura;
- rejeição de caminhos absolutos, `..`, `//` e nomes duplicados;
- EPUB tratado como entrada não confiável;
- nenhum texto protegido é copiado, impresso ou persistido pelo verificador.

## Garantias demonstradas

- Hash divergente/ausente e arquivo ausente não promovem identidade.
- OPF ausente/inválido, idioma/título/identificador incompatíveis não promovem.
- Arquivo alterado após a indexação produz `HASH_MISMATCH`/`INDEX_STALE`.
- Texto igual/diferente do EPUB é distinguido; unidade ausente/parcial também.
- Unidade de outro `attachmentId` não é resolvida globalmente.
- Seção divergente e texto repetido em outra unidade impedem verificação plena.
- Recurso de spine ausente/ilegível impede `COMPLETE_RELATIVE_TO_EPUB`.
- XHTML malformado gera `XHTML_INVALID` e impede completude positiva.
- IDs vazios ou duplicados no manifesto geram `MANIFEST_ID_MISSING`/
  `MANIFEST_ID_DUPLICATE` e impedem completude positiva.
- XHTML não bíblico sintaticamente válido (capa/navegação) continua aceito.
- ZIP com nome duplicado impede verificação positiva.
- Âncora com apenas identificador, ou mesmo com hash, não promove.
- A promoção factual permanece bloqueada em todos os cenários deste BUILD.

## Garantias ainda não demonstradas

- Autenticidade oficial da publicação: exige política externa de confiança na
  âncora, com origem independente e verificável.
- Identidade do registro persistido (`passageId`/`ord`/`section`): exige
  adaptador que consulte o índice Room real.
- Exposição ao LLM e suporte semântico: fora deste componente.
- Integração TRUST-2B2: PR posterior.
- Validação contra um EPUB real da TNM: os testes usam apenas EPUBs sintéticos.

## Integração futura

1. Mapear `AttachmentEntity` para `NwtAttachmentMetadata`.
2. Consultar `PassageEntity` por `attachmentId` + `ref` (nunca `findByRef`
   global) e montar `NwtIndexedUnit` com `passageId`/`ord`/`section` reais.
3. Executar o verificador.
4. Introduzir uma política externa de confiança na âncora; só então mapear
   `canPromoteUnitToLocalFactual` para `VERIFIED_LOCAL`.
5. Manter completude do corpus separada da recuperação da unidade.
6. Ligar ao registro de exposição do prompt em etapa posterior.
