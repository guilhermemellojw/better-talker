# Fase 6 — Verificação de fidelidade (documentação §33)

## 1. Arquitetura

```text
texto (bloco ativo ou conteúdo proposto)
  ↓  claimExtractor (regras locais: tipo + offsets, sem reescrita)
Generated/Edit Text → claims (factual|biblical|interpretive|application|creative|rhetorical|training)
  ↓  criativas/aplicações/retóricas => 💡 direto, sem retrieval
  ↓  factuais/bíblicas/interpretativas => HybridRetriever no escopo (track content)
  ↓  training => track training (BE/TH)
Claim ↔ Evidence matching (lexical+metadata+thresholds conservadores)
  ↓  números exigem literal; refs exigem correspondência; interpretive tem teto parcial
Support Assessment => supported|partially_supported|insufficient|creative + reason
  ↓  [opcional] LlmClaimJudge (contrato separado) — só qualifica com evidência citada
Proveniência (passageId, pub, título, seção, símbolo, página, parágrafo, ref, score)
  ↓  UI (sob demanda) / Preview da proposta
```

## 2. Modelo

`ExtractedClaim{id,text,type,blockId,start,end}`; `ClaimEvidence{claimId,
evidenceId,support: supports|partial|contradicts,score,reason,snippet,
provenance}`; `VerifiedClaim{claim,status,evidence,reason}`;
`TextVerification{claims,summary,limited,key}`. Tipos Fase-1 (`Claim`,
`ClaimStatus`) preservados, sem consumidores.

## 3. Regras conservadoras

- Dúvida => partial/insufficient, nunca supported.
- Números exigem presença literal (dígitos de referência excluídos).
- `interpretive` tem teto `partially_supported`.
- Juiz só promove partial→supported citando evidência válida; falha => local.
- Sem avaliador remoto: `limited=true` + legenda "Verificação local".

## 4. Stale e cache

Chave = hash(bloco + conteúdo + escopo). UI compara hash e exibe
"verificação obsoleta". Cache em memória (cap 50) invalidado por qualquer
componente da chave.

## 5. Testes (`npm test`)

19 novos (76 totais; Fases 3–5 intactas): extração (3), evidência (5),
referências (2), segurança/escopo/training/stale (5), juiz (4).

## 6. Correções que os testes revelaram

1. Splitter quebrava `w24.01` no ponto => proteção dígito-ponto-dígito.
2. Termos ausentes do corpus recebiam IDF máximo e puniam cobertura =>
   `idf` de df=0 agora é 0 (não discrimina, não pune). Fase-3 verde.
3. Dígitos de referência contavam como dados => scrub de citações em `numbersOk`.

## 7. Limitações

- NLI real inexistente; matching é lexical/metadata (documentado, não verdade).
- Sem score global de fidelidade (proposital — sem falsa precisão).
- Juiz LLM implementado mas NÃO ligado na UI (só local por padrão).
- Cache só em memória (não persiste).

## 8. Impacto futuro

- **Fase 7**: tipos `training`/`application` prontos para orientação BE/TH.
- Propostas Fase-5 verificáveis antes do aceite sem auto-aplicar.
