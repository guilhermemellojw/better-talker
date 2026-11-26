# TRUST-1A — Contrato de isolamento de evidência (proposta, sem implementação)

**Status:** proposta para revisão/autorização. Nenhum código foi alterado.  
**Base:** TRUST-0 (`docs/audits/TRUST-0-discovery-report.md`), `origin/main` `4210233`, PR #8 `26f9541`.  
**Escopo:** separação por construção entre contexto conversacional e evidência factual. Não cobre avaliação semântica (TRUST-2).

---

## 1. Objetivo

Garantir que verificadores factuais usem apenas conteúdo documental realmente recuperado, mesmo quando o LLM recebe contexto conversacional, memória e estrutura do S-34 no prompt.

Invariante central:

> Memória, hipótese do usuário, histórico, mera existência de referência e estrutura do S-34 **não** são prova documental.

---

## 2. Tipos de contexto (contrato)

### 2.1 `ConversationContext` (prompt-only)

Conteúdo:
- pergunta atual do usuário;
- histórico recente;
- hipóteses do usuário;
- tentativas de refutação;
- decisões pendentes.

Uso:
- pode entrar no prompt para continuidade e personalização;
- **nunca** entra em corpus factual;
- não autoriza claim factual.

### 2.2 `WritingMemory` (prompt-only)

Conteúdo:
- `agreedApproach`, preferências, decisões do usuário;
- resumo rolante da conversa.

Uso:
- orienta estilo/redação;
- **nunca** entra em corpus factual;
- já parcialmente isolada em PR #8 via `buildEvidenceBlock(includeApproach = false)`.

### 2.3 `StructuralContext` (estrutura, não prova)

Conteúdo:
- títulos, objetivos, ordem de pontos;
- refs do S-34 (rótulos);
- subpontos;
- metadados de seção.

Uso:
- guia geração e navegação;
- rótulos de refs podem aparecer como metadados;
- **não** autoriza conteúdo da ref sem texto recuperado.

### 2.4 `EvidenceCorpus` (única fonte factual)

Conteúdo permitido:
- `ContextPack` sources com texto não vazio, conforme a proveniência da §2.5;
- dossiê via `buildEvidenceBlock` (prosa, refs com texto, textos resolvidos);
- versículos/publicações/capítulos resolvidos com texto não vazio;
- fontes do assistente global com texto recuperado;
- refs do S-34 **somente** quando houver texto recuperado (`rawText`/resolved text não vazio).

Conteúdo proibido:
- `userText`;
- `userHistory`;
- memória/abordagem;
- títulos sem conteúdo;
- refs sem texto;
- fontes waived;
- be/th como prova doutrinal (são `TRAINING`, não `CONTENT`);
- S-34 contendo apenas referência.

### 2.5 Proveniência mínima para entrar no `EvidenceCorpus`

Um trecho só pode ser adicionado se tiver:

1. identificador de origem (`sourceId`, ref ou unidade);
2. `sourceType`/categoria de origem (`CONTENT`, `TRAINING`, `STRUCTURAL`);
3. texto não vazio efetivamente recuperado;
4. para ref de S-34, texto resolvido por `ReferenceResolver` (não apenas `rawText`);
5. para dossiê, saída de `buildEvidenceBlock` (que omite abordagem);
6. para assistente global, texto vindo da fonte da Biblioteca.

Texto de seção, objetivo, subponto, nota do orador ou rótulo de referência **não** são evidência documental apenas por existirem no S-34. Eles podem compor `StructuralContext` e prompt, mas não autorizam afirmação factual sobre a fonte.

### 2.6 Fontes de treinamento (be/th)

- `TRAINING` participa da orientação oratória (como apresentar), nunca como prova doutrinal, bíblica ou histórica.
- `TRAINING` não sustenta automaticamente afirmações factuais. Só pode sustentar afirmações explicitamente sobre técnica/apresentação, e mesmo assim deve ser identificado como orientação.
- Para afirmações bíblicas/editoriais/históricas, apenas `CONTENT` (fonte documental recuperada) conta.
- A ambiguidade anterior (`CONTENT` e `TRAINING`?) fica resolvida: `TRAINING` é contexto de método, não evidência factual geral.

### 2.7 Corpus S-34 (`s34CorpusTexts`)

Distinguir:
- texto escrito no esboço (dado do usuário, não prova de fonte);
- referência citada pelo esboço (metadado);
- texto bíblico/editorial efetivamente recuperado (única base para alegações sobre conteúdo da fonte).

Somente o terceiro pode autorizar afirmações sobre o conteúdo da fonte.

---

## 3. Regra de cidadania da pergunta do usuário

O Mentor pode:
- citar a pergunta do usuário;
- parafraseá-la;
- usá-la para responder ou refutar.

Mas:
- a pergunta não é evidência;
- citações da pergunta no answer devem ser tratadas como fala do usuário, não como fonte;
- o verificador factual não deve tratar texto do usuário como corpus.

### 3.1 Citação atribuída ao usuário

O Mentor pode citar literalmente a pergunta quando isso ajudar a explicar ou refutar uma hipótese.

Requisitos:
- a citação deve ser identificada como fala do usuário (atribuição explícita);
- não pode ser tratada como evidência documental;
- não depende apenas de instruir o modelo a evitar aspas.

Implicação de implementação (futura): o verificador deve distinguir citações atribuídas ao usuário de citações factuais antes da checagem de aspas. A distinção deve ocorrer por construção (marcador/atribuição), não apenas por prompt.

---

## 4. Caminhos que usam `verificationCorpus` (inventário)

| Caminho | Função/arquivo | Corpus atual | Ação futura |
|---|---|---|---|
| Chat remoto | `CopilotViewModel.answerRemote` → `chatVerificationCorpus` (`CopilotViewModel.kt:287-308`, chamada ~5470) | Inclui `userText`, `userHistory`, refs, dossiê | Remover user text/history; manter evidência |
| Explicação de fontes (modo fonte) | `CopilotViewModel.answerRemote` (`CopilotViewModel.kt:5456-5475`) | Inclui `text` do usuário no corpus | Remover user text; manter apenas texto recuperado da fonte |
| Verificação de chat | `verifyChatText` (`CopilotViewModel.kt:468`) | Recebe corpus montado acima | Sem mudança de assinatura; apenas corpus |
| Geração/insert | `verifyGeneratedText` (`GenerationVerification.kt:38-60`) | `verificationCorpus(turnContext)` + extras | Garantir extras são evidência |
| Corpus S-34 | `s34CorpusTexts` (`GenerationVerification.kt:12-36`) | Objetivo, nota do orador, seções, refs `rawText` | Excluir refs sem texto resolvido |
| Oratória/card | `CopilotViewModel` card path (~4860-4890) | `turnContext` + evidence + texto da seção | Mesmo contrato |
| Global assistant | `GlobalAssistantViewModel` + `CitationContract` | Fontes da Biblioteca | Adicionar tipo `AUTHORIZED_SOURCE` |
| Source question mode | `answerRemote` ~5456-5475 | Bloco da fonte + refs detectadas | Manter apenas texto recuperado |

---

## 5. Critérios para cenários obrigatórios

| Cenário | Critério esperado |
|---|---|
| Hipótese falsa do usuário | Não pode ser tratada como evidência; resposta pode refutar usando fonte ou limitação |
| Hipótese correta não comprovada | Deve ser rotulada como hipótese/sugestão, não fato |
| Fonte documental recuperada | Entra em `EvidenceCorpus` |
| Referência mencionada sem texto | Não entra em `EvidenceCorpus`; resposta deve dizer que o texto não está disponível |
| Fonte citada que não sustenta interpretação | Corpus contém texto; verificação sintática não resolve (TRUST-2) |
| Corpus vazio | Verificador retorna texto intacto (comportamento atual preservado) |
| S-34 com apenas referência | Ref não autoriza conteúdo |
| Objetivo/subpontos do S-34 no corpus | Não são evidência documental automaticamente |
| Citação atribuída ao usuário | Permitida como fala do usuário, não como evidência |
| Corpus documental vazio | Sem proteção sintática; resposta deve ser limitada/honesta |
| Memória de decisão com versículo sem texto | Memória não é corpus |
| Respostas com/sem citações literais | Quote check só contra `EvidenceCorpus` |

---

## 6. Arquivos que a implementação futura precisaria alterar

1. `android/.../ui/copilot/CopilotViewModel.kt`
   - `verificationCorpus` (261-284)
   - `chatVerificationCorpus` (287-308)
   - `answerRemote` montagem de corpus (~5456-5475)
2. `android/.../ui/copilot/GenerationVerification.kt`
   - `s34CorpusTexts` (12-36)
   - `verifyGeneratedText` (38-60)
3. `android/.../ui/assistant/GlobalAssistantViewModel.kt`
   - caminho de citação (354-395)
4. `android/.../data/copilot/SourceGate.kt`
   - waived/evidência (40-157)
5. `android/.../data/copilot/DossierPromptBuilder.kt`
   - `buildEvidenceBlock` (95-105)
6. `android/.../data/copilot/OratoryFidelityCheck.kt`
   - corpus autorizado (60-135)
7. `android/.../data/copilot/CitationContract.kt`
   - contrato de fonte (31-70)
8. Testes:
   - `VerificationCorpusTest.kt`
   - `OratoryVerificationCorpusTest.kt`
   - `SourceGateTest.kt`
   - `CitationContractTest.kt`
   - `GroundednessNormalizationTest.kt`
   - `OratoryFidelityCanonicalTest.kt`, `OratoryFidelityCleanupTest.kt`
   - `DossierFidelityCheckTest.kt`

---

## 7. Plano mínimo de implementação (TRUST-1A, não executar agora)

1. Introduzir (em código, futuramente) a distinção de tipos de contexto sem mudar prompts.
2. Alterar `chatVerificationCorpus` para não aceitar `userText`/`userHistory`.
3. Alterar o ramo `sourceQuestionMode` de `answerRemote` para não incluir `text` do usuário no corpus.
4. Alterar `s34CorpusTexts` para incluir apenas refs com texto resolvido e distinguir texto do esboço, ref citada e texto recuperado.
5. Garantir que `verifyGeneratedText` filtre extras vazios (já faz) e documente que extras devem ser evidência.
6. Exigir proveniência mínima (§2.5) para todo trecho do `EvidenceCorpus`.
7. Definir `TRAINING` como método, não evidência factual geral (§2.6).
8. Implementar distinção de citações atribuídas ao usuário antes da checagem de aspas (§3.1).
9. Atualizar prompt rules para atribuição indireta da fala do usuário.
10. Adicionar testes de regressão para os cenários da §5 e extensão T1A.
11. Manter warn-only/remoção existentes sem mudança silenciosa.

Não incluir avaliação semântica (TRUST-2).

---

## 8. Dependências do PR #8 e riscos de integração

- PR #8 introduziu `evidenceContextBlock` e exclusão da memória do corpus. TRUST-1A depende dessa separação.
- Se TRUST-1A for implementado antes da integração do PR #8, haverá conflito em `CopilotViewModel.answerRemote` e `chatVerificationCorpus`.
- Recomendação: integrar PR #8 (M0) antes de TRUST-1A, ou rebasear TRUST-1A sobre o PR #8.
- Risco: testes existentes (`VerificationCorpusTest`) podem quebrar com remoção de `userText`/`userHistory`; atualizar testes de forma explícita, não silenciosa.

---

## 9. Limitações, limites explícitos e hipóteses não verificadas

Limites explícitos do TRUST-1A:
- TRUST-1A garante isolamento de proveniência, não correção semântica.
- Corpus vazio não significa resposta comprovada.
- Ausência de aviso sintático não significa que a afirmação esteja fundamentada.
- TRUST-2 continua responsável por avaliar a relação entre alegação e trecho documental.

Hipóteses não verificadas:
- Não foi possível validar em aparelho (sem build/teste).
- Não se sabe a cobertura real de refs resolvidas em notas reais.
- A eficácia da atribuição indireta no prompt depende do modelo.
- TRUST-1A não resolve interpretação semântica (TRUST-2).
- Corpus vazio continua sem proteção factual (comportamento atual preservado por requisito).

**Fim da proposta TRUST-1A.**

---

## 10. Reconciliação documental — 25/11/2026 (append-only)

Baseline anterior (`origin/main` `4210233`, PR #8 `26f9541`) preservado como histórico nas seções 1–9. **Baseline vigente:** `origin/main` = `5e65e562cd9fde41a4a5d70965c3a4fc69f4cd00`; HEAD auditado do #11 = `d917df70be1f614baaf60a4784dc5af725e05c07`. Desde a proposta, a main integrou #42, #43, #48, #52 e #60 (parecer independente E, comentário 7172233017). Nenhuma auditoria certificada foi repetida nesta reconciliação.

### 10.1 Classificação vigente dos requisitos

| Requisito | Estado vigente | Referência estável (função; sem número de linha) |
|---|---|---|
| §2.1/§2.2 conversa e memória fora do corpus | **SATISFEITO** | `verificationCorpus()`, `EvidenceCorpus.documentaryTexts()`, `buildEvidenceBlock(includeApproach = false)` |
| §2.3 S-34 não autoriza conteúdo | **SATISFEITO** | `s34CorpusTexts`, itens STRUCTURAL com `resolved = false` |
| §2.4/§2.5 proveniência mínima | **SATISFEITO** | `EvidenceItem`, `EvidenceProvenance.isDocumentaryEvidence()` |
| §2.6 be/th como TRAINING | **SATISFEITO** | categoria TRAINING separada; `contentHits()` exclui guias |
| §2.7 esboço ≠ ref ≠ texto recuperado | **SATISFEITO** | `documentaryTexts()` exige `resolved`, texto não vazio e `!partial` |
| §3.1 citação atribuída ao usuário | **PARCIAL** | `isRolePlayQuote()`/`rolePlayQuoteCount()` e `ChatBilingualSupport.noticeWithBiblicalSafety()` — atribuição indireta ainda depende de prompt + heurística, sem marcador por construção |
| §4 remover `userText`/`history` do corpus | **SATISFEITO** | `sourceQuestionVerificationCorpus()`, `chatVerificationCorpus()` (#60) |
| Gate oratório / atomicidade / fail-safe / rótulo | **SATISFEITO** | `SourceGate.decide()`, `NwtRoomIndexIdentityAdapter.verify()` com `RoomTransactionRunner`, `GroundednessVerifier.verify()`, `ChatBiblicalSupport.retrievedExcerpt()` (#42/#43/#48/#60) |
| TRUST-2 semântico amplo e cobertura ampla de paráfrases | **PÓS-BETA** | `SemanticVerification.hasDocumentarySupport()` permanece domínio puro, sem integração; paráfrase é `non liquet`, nunca "verificado" |

### 10.2 Substituição de referências de linha por funções estáveis

As referências `arquivo:linha` das seções anteriores são históricas e não devem ser usadas para localizar código na main atual. Substituições vigentes: `chatVerificationCorpus` → `verificationCorpus()`; `verifyChatText` → `verifyChatText()` com deduplicação tipada; `SourceGate.decide` → `SourceGate.decide()`; `CitationContract.check` → `CitationContract.check()`; `GroundednessVerifier.verify` → `GroundednessVerifier.verify()`; `buildEvidenceBlock(false)` → `DossierPromptBuilder.buildEvidenceBlock()`; `biblePassages/publicationPassages` → `CopilotRepository.biblePassages()/publicationPassages()`; `answerAsk` → `CopilotViewModel.answerAsk()` com `retrievedExcerpt()`.

### 10.3 Limitações preservadas

- Atribuição indireta depende de prompt e heurística certificada — **não** é garantia por construção.
- Corpus vazio segue sem proteção sintática adicional por requisito explícito; ausência de aviso não é aprovação factual.
- Validação em aparelho/checkpoint físico permanece **gate obrigatório do beta controlado** (BT-E2E-001, matriz de E, preparação de D); nenhum marco foi promovido a certificado físico.
- O adiamento de TRUST-2 semântico amplo **não** torna aceitáveis afirmações factuais sem apoio documental elegível.
- Reconciliação estritamente documental, sem código, nova política TRUST, interferência em #62 ou reauditoria de #48/#52/#60.
