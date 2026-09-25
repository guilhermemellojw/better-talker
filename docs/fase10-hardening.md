# Fase 10 — Hardening e validação (documentação §36)

## 1. Bugs encontrados

1. **Import errado em teste novo** (`importParse` apontava `../outlineParser`):
   suíte quebrava por módulo inexistente. Corrigido para o caminho real.
2. **Expectativa de prompt incorreta** (regex esperava "NÃO usar como fatos";
   texto real: "nunca as use como fonte de fatos"). Corrigido o teste, não o prompt.
3. **TS strict em teste novo** (`matchedTerms`/`foundBy` opcionais). Corrigido
   com fallback explícito.
4. **Dependência de código não-commitado (Fase 8, pego pela validação isolada)**:
   pré-existente, fora do escopo F10 — registrado aqui como lição (ver §13).

Nenhum bug estrutural (regra §38): nenhuma correção usou exceção para teste.

## 2. Bugs corrigidos

Os 3 acima (todos em código/teste da própria Fase 10, antes do commit).

## 3. Testes adicionados

Web (50 novos, 6 arquivos): `flow` (5: e2e F9→F5→F6, rejeição, stale,
redo-invalidado, atomicidade), `robustness` (19: vazios, extremos, acentos,
cache, segurança), `providersExtra` (6: 5xx, contrato), `contextPackMatrix`
(9: matriz §8, proveniência, não-evidência, ilustração), `importParse` (6),
`performance` (3: 600 passages/13ms, 150 blocos/98ms, 20 claims/3ms).
Android (10 novos): `MigrationSqlTest` (4, SQL real em SQLite),
`HardeningTest` (6: escopo vazio, troca de escopo, extremos, acentos,
legado, training vazio).

## 4. Fluxos end-to-end

F9→brief→Fake→parse→F6→apply→undo→redo→nova edição (tudo verde);
rejeição sem resíduo; stale bloqueia; multi-op atômica.

## 5. Offline

Tudo local (análise, retrieval por store, verificação, classifier) roda com
`fetch` inexistente — por construção (nenhuma chamada de rede no caminho).
Remoto ausente => `ProviderError` (`unavailable`), nunca sucesso falso.
Persistência web (Dexie/localStorage) e Room real: sem automação neste
ambiente (sem navegador/instrumentado) — checklist manual em §13.

## 6. Cache

`analysisCache`/`verifyCache`: hit por identidade, invalidação por
conteúdo/escopo, cap 50 com eviction (testado até 55 entradas).
`ANALYSIS_VERSION` compõe a chave.

## 7. Persistência

Web: caminho `saveSpeech→Dexie→backup localStorage` inalterado; sem teste
automatizado aqui (requer navegador). Android: schema v10 validado em
compilação; dados reais exigem aparelho/emulador (dívida §13).

## 8. Migration

`MIGRATION_9_10` extraída para `MigrationSql` (puro) e executada de verdade
em SQLite via `sqlite-jdbc`: colunas, defaults, backfill be/th→training e
nwt→bible, dados intactos, índices criados. Dívida restante: caminho Room
completo (`MigrationTestHelper`) exige instrumentado.

## 9. Compatibilidade

Codec legado sem `tc`/`f` (Fase 8, revalidado); registros Dexie v2 sem
`source_type` (fallback); `Passage` sem metadata (nulls, nunca fictício);
entidades Room antigas (defaults).

## 10. Performance (medido, não otimizado)

Retrieval 600 passages/3 escopos: **13ms**; análise 150 blocos: **98ms**;
verificação 20 claims: **3ms**. Sem duplicatas nos hits; proveniência
intacta em corpus maior.

## 11. Acessibilidade

Revisado: botões com `title` ou rótulo textual; glyphs (✓⚠?💡📖🎤) sempre
acompanhados de texto ("com suporte", "Relevância", categoria). Nenhuma
mudança necessária.

## 12. Matriz de confiabilidade

| Área | Testado | Resultado | Observação |
| ---- | ------- | --------- | ---------- |
| Retrieval | ✓ | 165 web verdes | +13ms/600 |
| Training | ✓ | verde | trilho isolado |
| ContextPack | ✓ | verde | matriz §8 |
| F5 Editing | ✓ | verde | e2e + stale |
| F6 Verification | ✓ | verde | conservador |
| F9 Analysis | ✓ | verde | 98ms/150 |
| Offline | ✓ | por construção | sem fetch no caminho |
| Persistence | parcial | manual | sem navegador/aparelho |
| Migration | ✓ | SQL real executado | Room completo: dívida |
| Providers | ✓ | verde | 5xx coberto |
| Import | ✓ | verde | docx binário: degrada, documentado |
| UI | revisão | ok | estados existem |
| Android | ✓ | 160 verdes | +migration SQL |

## 13. Limitações, dívidas e riscos

1. Persistência web/Android real: checklist manual (sem ambiente).
2. `MigrationTestHelper` instrumentado: dívida (SQL validado).
3. `.docx` binário não extraído (`mammoth` sem uso) — degradação graciosa
   testada; extração real é trabalho futuro, não bug F10.
4. Semântica real inexistente (herdado).
5. `npm test` inicial lento a frio (~90s transform); sem mudança.
6. Risco: track paralelo edita os mesmos arquivos Android — commits por
   hunk cirúrgico quando necessário.

## 14. Duplicação web × Android (§32)

- Intencional e documentada: stopwords de retrieval (domínio autocontido
  em cada lado), keywords de training (mesmas regras), normalização
  (APIs de plataforma diferentes).
- Acidental: nenhuma encontrada para corrigir com segurança.
