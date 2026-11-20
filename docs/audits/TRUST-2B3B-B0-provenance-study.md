# TRUST-2B3B-B0 — Estudo de proveniência editorial da TNM

## Objetivo

Investigar opções verificáveis para a futura camada de autenticidade editorial da Tradução do Novo Mundo (TNM), sem presumir que exista infraestrutura oficial disponível.

**Não implementar autenticação oficial nesta fase.**

## Dados que poderiam comprovar origem oficial

1. **Hash criptográfico (SHA-256) do arquivo EPUB** — verificável offline, mas só prova integridade em relação a um hash previamente conhecido; não prova origem.
2. **Assinatura digital do EPUB** (PKCS#7/X.509, padrão EPUB 3 `META-INF/signatures.xml`) — provaria origem se a chave privada fosse da organização; depende de chave pública confiável e de cadeia de certificação.
3. **Manifesto autenticado** (OPF com assinatura ou hash publicado oficialmente) — provaria que o manifesto não foi adulterado.
4. **Metadados oficiais publicados** (título, idioma, editora, identificador, data de modificação) — apenas consistência interna, não autenticidade.
5. **Fonte de distribuição verificável** (URL oficial, certificado TLS, CDN) — prova de origem de rede, não de arquivo.

## Vinculação de uma âncora verificável a uma edição e a um arquivo exato

- A âncora (`NwtTrustedAnchor`) deve conter: identificador da edição (ISBN/UUID oficial), hash SHA-256 do arquivo exato, data de publicação e origem da âncora (quem/onde a forneceu).
- A âncora deve ser obtida de uma fonte independente e verificável (não do próprio arquivo).
- Vinculação: `anchor.sha256 == sha256(file)` AND `anchor.identifier == opf.identifier` AND `anchor.editionDate == opf.modified` (quando disponível).
- A correspondência (`ANCHOR_MATCH`) não é autorização: um UUID pode ser copiado e um hash pode ser calculado sobre um arquivo adulterado.

## Existência de fonte oficial para hash/assinatura/manifesto autenticado

- **Não há evidência** de que o site jw.org ofereça um endpoint de hashes, assinaturas ou manifesto autenticado utilizável.
- O EPUB da TNM não contém, em geral, `META-INF/signatures.xml` (assinatura digital).
- Os metadados OPF (título, idioma, editora, identificador) são preenchidos pelo publicador e podem ser copiados.
- Conclusão: **não presumir que a infraestrutura exista**. Se não existir, a limitação deve ser documentada (como feito no TRUST-2B3B-A).

## Distinguir metadados oficiais de valores preenchidos pelo usuário

- Metadados oficiais: provenientes de uma fonte independente (assinatura, manifesto autenticado, registro oficial).
- Valores preenchidos pelo usuário: editáveis no import (baseSlot, symbol, attachmentId, etc.).
- O verificador atual (`NwtDocumentIdentityVerifier`) trata o OPF como consistência editorial (`IDENTITY_CONSISTENT`), não como autenticidade. Isso deve permanecer.

## Registrar origem e data de uma âncora

- Armazenar a âncora em uma tabela/registro separado, com: `anchorId`, `editionIdentifier`, `fileSha256`, `editionDate`, `source` (URL ou referência), `obtainedAt` (timestamp), `obtainedBy` (usuário/dispositivo).
- A âncora deve ser imutável após criação; revisões criam novas âncoras.
- Offline: quando não há prova disponível, o verificador retorna `NOT_ANCHORED` e a promoção permanece bloqueada (comportamento atual).

## Novas edições e revisões

- Cada edição/revisão tem seu próprio identificador e hash.
- A âncora deve ser específica por edição; não reutilizar âncoras entre edições.
- Revisões incrementais exigem nova âncora e nova verificação.

## Ação offline sem prova disponível

- O verificador retorna `NOT_ANCHORED` (já implementado).
- `anchorProvenanceTrusted = false` (já implementado).
- Promoção factual permanece bloqueada (já implementado).
- O usuário pode usar o conteúdo localmente, mas sem status de autenticidade oficial.

## Limitações conhecidas

- Sem infraestrutura oficial de hash/assinatura, a autenticidade editorial não pode ser comprovada por este verificador.
- Correspondência de hash/identificador com uma âncora fornecida pelo usuário não prova origem oficial.
- A cadeia de confiança completa (autenticidade + identidade + integridade) exige componentes futuros (trust-2b3b-b1 ou posterior) e revisão independente.

## Conclusão

A autenticação oficial da TNM **não está disponível** com a infraestrutura atual. A limitação deve ser documentada e preservada. Nenhuma implementação de autenticação oficial foi feita nesta fase. A promoção factual permanece bloqueada.