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

## Entrada

`NwtDocumentIdentityVerifier.verify(...)` recebe:

- `NwtAttachmentMetadata`: `attachmentId`, nome, kind, baseSlot, symbol,
  indexed, status, SHA-256 registrado, tamanho e caminho;
- `File`: o EPUB atual;
- `NwtIndexedUnit?`: `attachmentId`, `passageId`, referência canônica, `ord`
  e texto indexado;
- `NwtExpectedEdition`: perfil de consistência editorial e, opcionalmente,
  uma âncora externa previamente aprovada;
- `NwtVerifierLimits`: tetos de XML, XHTML, entradas e tamanho descomprimido.

## Saída

`NwtDocumentIdentity` contém:

| Campo | Significado |
|---|---|
| `integrity` | `FILE_MISSING`, `FILE_UNREADABLE`, `NOT_EPUB`, `HASH_MISSING`, `HASH_MISMATCH`, `HASH_MATCH` |
| `editorialState` | `OPF_MISSING`, `OPF_INVALID`, `METADATA_INCONSISTENT`, `IDENTITY_UNVERIFIABLE`, `IDENTITY_CONSISTENT` |
| `authenticity` | `NOT_ANCHORED`, `ANCHOR_MATCH`, `ANCHOR_MISMATCH` |
| `unit.state` | `UNIT_MISSING`, `UNIT_PARTIAL`, `UNIT_VERIFIED`, `INDEX_STALE`, `UNIT_UNVERIFIABLE` |
| `unit.comparison` | `EXACT`, `NORMALIZED_WHITESPACE`, `MISMATCH`, `NOT_COMPARED` |
| `corpus.state` | `COMPLETE_RELATIVE_TO_EPUB`, `PARTIAL`, `INCONSISTENT`, `UNKNOWN` |
| `promotion` | elegibilidade derivada para promoção futura, sem `VERIFIED_LOCAL` |

Não existe `VERIFIED_LOCAL` na saída. A promoção futura exige todos os checks
abaixo **e** uma âncora editorial externa (`NwtTrustedAnchor`):

- integridade `HASH_MATCH`;
- metadados do anexo consistentes;
- estrutura EPUB válida;
- identidade editorial `IDENTITY_CONSISTENT`;
- autenticidade `ANCHOR_MATCH`;
- unidade `UNIT_VERIFIED`;
- para nível de corpus, `COMPLETE_RELATIVE_TO_EPUB`.

`ANCHOR_MATCH` significa apenas "coincide com a âncora fornecida". O verificador
não cria uma autoridade oficial nem declara autenticidade oficial.

## Completude

A completude é medida contra o próprio EPUB:

- spine/OPF e documentos de livro identificados;
- marcadores `chapterN` / `chapterN_verseM`;
- versículos efetivamente extraídos pelo parser;
- 66 livros identificáveis no catálogo NWT.

O verificador **não** compara com 31.102 (KJV) nem com qualquer contagem de
outra tradução. Omissões específicas da edição (por exemplo, João 8:1-11) não
são tratadas como truncamento do índice.

`COMPLETE_RELATIVE_TO_EPUB` não significa "Bíblia canônica completa"; significa
que todo o conteúdo de livro/spine que o verificador conseguiu enumerar foi
indexado sem divergência entre marcadores e parser.

## Segurança

- `ZipFile` com limite de entradas e tamanho descomprimido;
- leitura limitada de `container.xml`, OPF e XHTML;
- XML com `FEATURE_SECURE_PROCESSING`, DOCTYPE desabilitado, entidades externas
  desabilitadas e `EntityResolver` vazio;
- rejeição de caminhos absolutos, `..`, `//` e entradas duplicadas críticas;
- EPUB tratado como entrada não confiável;
- nenhum texto protegido é copiado, impresso ou persistido pelo verificador.

## Garantias demonstradas

- Hash divergente, hash ausente e arquivo ausente não promovem identidade.
- OPF ausente/inválido, idioma/título/identificador incompatíveis não promovem.
- Arquivo alterado após a indexação produz `HASH_MISMATCH`/`INDEX_STALE`.
- Texto indexado igual ao extraído produz `UNIT_VERIFIED`; diferente produz
  `INDEX_STALE`; unidade ausente produz `UNIT_MISSING`; identidade incompleta
  produz `UNIT_PARTIAL`.
- Referência pertencente a outro `attachmentId` não é resolvida globalmente.
- Metadados coerentes sem âncora externa **não** promovem.
- Âncora externa divergente **não** promove.
- Completude de um único livro não vira `COMPLETE_RELATIVE_TO_EPUB`.
- EPUB sintético com 66 livros e todos os marcadores coerentes vira
  `COMPLETE_RELATIVE_TO_EPUB`.

## Garantias ainda não demonstradas

- Autenticidade oficial da publicação: depende de uma âncora/manifesto externo
  aprovado pelo mantenedor.
- Exposição ao LLM: não faz parte deste componente.
- Suporte semântico: não faz parte deste componente.
- Integração com TRUST-2B2: será um PR posterior.
- Validação contra um EPUB real da TNM: os testes usam apenas EPUBs sintéticos;
  a validação real deve ocorrer no BUILD seguinte, com proveniência autorizada.

## Integração futura

1. Mapear `AttachmentEntity` para `NwtAttachmentMetadata`.
2. Consultar `PassageEntity` por `attachmentId` + `ref` (nunca `findByRef`
   global) e montar `NwtIndexedUnit`.
3. Executar o verificador.
4. Mapear `promotion.canPromoteUnitToLocalFactual` para `VERIFIED_LOCAL` somente
   quando a política externa de âncora estiver aprovada.
5. Manter `corpusCompleteRelativeToEpub` separado da recuperação da unidade.
6. Ligar o resultado ao registro de exposição do prompt em etapa posterior.
