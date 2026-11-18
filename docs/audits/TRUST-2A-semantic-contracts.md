# TRUST-2A — Contrato de verificação semântica e vínculo documental

**Status:** implementação de domínio puro. Sem integração às rotas, sem LLM, sem dependências novas.  
**Base:** `bc8857050bbb9876dc035cf1d989658105dd617d` (TRUST-1A2 integrada no PR #16).  
**Código:** `android/app/src/main/java/com/bettertalker/app/data/copilot/SemanticVerification.kt`.  
**Testes:** `android/app/src/test/java/com/bettertalker/app/data/copilot/SemanticVerificationTest.kt`.

## 1. Contratos implementados

### Afirmação

- `ClaimType`: factual bíblica, doutrinal, histórica, técnica oratória, hipótese do usuário, citação literal, sugestão criativa e estrutural/editorial.
- `ClaimSpan`: intervalo na resposta.
- `ClaimReference`: referência citada + `documentId` recuperado (ou null).
- `Claim`: id, texto, span, tipo, referências e IDs de evidências candidatas.

### Evidência

- `EvidenceUnit` reutiliza `EvidenceItem`/`EvidenceProvenance`; preserva `documentId`, referência, tipo, edição, unidade, texto, truncamento e vínculo.
- `DocumentKind`: BIBLE, PUBLICATION, S34, USER_MESSAGE, TRAINING.
- `isFactualEligible()`: apenas BIBLE/PUBLICATION com `CONTENT` resolvido, não dispensado, texto não vazio e sem truncamento.
- `RecoveredDocument.asEvidenceUnit(...)` evita segunda cópia independente do conteúdo.

### Resultados

- `SemanticState`: `SUPPORTED`, `INSUFFICIENT`, `CONTRADICTED`, `UNVERIFIABLE`, `NOT_EVALUATED`.
- `ClaimVerdict`: estado, evidências examinadas, confiança e motivo. `SUPPORTED` exige evidência identificável; `NOT_EVALUATED` não declara evidência examinada.
- `ClaimAssessment`: amarra claim + veredito + unidades; rejeita IDs inexistentes; `SUPPORTED` só para claim factual com evidência documental elegível.
- `SemanticVerificationResult`: agregação conservadora; corpus vazio → `NOT_EVALUATED`; nunca `SUPPORTED` por omissão.

### Fidelidade literal

- `CitationOrigin`: DOCUMENT, USER_MESSAGE, S34.
- `CitationFidelityState`: EXACT, NORMALIZED, NOT_FOUND, UNVERIFIABLE, NOT_EVALUATED.
- `CitationFidelity.authorizesFactualClaim` é sempre `false`.
- `checkDocumentCitation` compara contra **uma** unidade; não junta documentos.
- `checkUserCitation` usa `AttributedUserQuote.trustedOriginalMessage`.
- `checkS34Citation` confirma fidelidade ao S-34, sem autoridade factual.

## 2. Invariantes

1. Nenhum construtor atribui `SUPPORTED` por padrão.
2. `SUPPORTED` exige IDs examinados e evidência elegível: documental para claims factuais; de método (be/th) para técnica oratória; nunca para hipótese do usuário, citação literal, criativo ou estrutural.
3. Citação fiel (documental, usuário ou S-34) não autoriza afirmação factual.
4. Evidência estrutural, conversacional, memória ou treinamento não é factual.
5. `EvidenceUnit` BIBLE/PUBLICATION exige unidade documental.
6. `kind` incompatível com `EvidenceCategory` falha na construção.
7. Texto truncado não é elegível para `SUPPORTED`; ausência de citação em fonte truncada é `UNVERIFIABLE`.
8. Corpus vazio resulta em `NOT_EVALUATED`/`UNVERIFIABLE`, nunca confirmação.
9. Resultados são imutáveis (data classes) e preservam IDs de origem.

## 3. Compatibilidade com TRUST-1A2

- Reutiliza `EvidenceItem`, `EvidenceProvenance`, `RecoveredDocument` e `AttributedUserQuote`.
- Não altera `GroundednessVerifier`, `CitationContract`, `SourceGate`, `OratoryFidelityCheck` ou rotas.
- Não cria sistema paralelo de evidências.
- `EvidenceUnit` é uma camada de avaliação sobre o item documental existente.

## 4. Contrato para UX-REF-1 (issue #21)

Para que `(Mat. 24:14)` abra o conteúdo real no leitor:

1. O claim deve conter `ClaimReference(raw = "Mat. 24:14", documentId = <id recuperado>)`.
2. `EvidenceUnit.documentId` deve ser o mesmo ID usado pelo leitor/Biblioteca.
3. Se `documentId == null`, a referência não foi recuperada: não pode ser apresentada como disponível ao Mentor.
4. O leitor pode mostrar o texto; isso é leitura documental, não aprovação semântica.
5. `CitationFidelity` de documento pode ser `EXACT`/`NORMALIZED` sem `SemanticState.SUPPORTED`.

Não implementar UI, links ou Bottom Sheets nesta fase.

## 5. Testes cobertos

- evidência válida/rastreável;
- corpus vazio;
- fonte não resolvida, dispensada, parcial e truncada;
- documento sem identificação/unidade;
- separação BIBLE/PUBLICATION × S34/USER_MESSAGE/TRAINING;
- claim não avaliada;
- `SUPPORTED` com evidência;
- `INSUFFICIENT`, `CONTRADICTED`, `UNVERIFIABLE`;
- citação fiel do usuário sem autorização factual;
- citação do S-34 sem autorização factual;
- fidelidade documental por unidade, sem juntar fontes;
- normalização legítima e truncamento;
- identidade documental Mentor↔leitor;
- imutabilidade e agregação conservadora.

## 6. Limitações

- Não há extração de claims por LLM.
- Não há motor semântico/NLI.
- Não há integração às rotas.
- `CitationFidelity` não é score de verdade.
- `SUPPORTED` só será emitido por fase posterior, com avaliação real.
- Testes no Galaxy A34 serão obrigatórios nas fases de integração/UX.
