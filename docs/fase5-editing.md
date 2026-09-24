# Fase 5 — Edição assistida (documentação §24)

## 1. Arquitetura

```text
Copilot UI (modo: suggest|rewrite|improve|insert|delete)
  ↓  alvo = bloco ativo (app; modelo nunca escolhe alvo)
ContextPack (só fontes autorizadas, Fase 3)
  ↓
LlmProvider.generate({ responseFormat: 'edit-proposal', editMode })
  ↓  ```json { explanation, operations[] }
proposalParser (forma válida? alvo coagido? delete-via-LLM vetado?)
  ↓
preview ANTES/DEPOIS → [Aceitar] [Rejeitar]
  ↓  aceitar: validateEditProposal → applyEditProposal (atômico) → EditHistory
```

`suggest` mantém o fluxo de texto da Fase 4 (nada muda sozinho).
`delete` é proposta local determinística, sem LLM.

## 2. Contrato de edição

`EditOperation` por ID de bloco (sem offsets): `insert{targetId,position: before|after,contentHtml}`,
`replace{targetId,contentHtml}`, `delete{targetId}`. `CopilotEditProposal`
carrega `baseHashes` (FNV-1a do `contentHtml` no momento da geração).

## 3. Validação e stale

`validateEditProposal` checa: alvos existem, posição válida, conteúdo
não-vazio (≤20000 chars), nunca deletar o último bloco, e hashes-base
iguais ao atual. Divergência => `stale_proposal` (nada aplicado, UI pede
nova geração). Concorrência resolvida sem versionamento global.

## 4. Undo/redo

`EditHistory`: snapshots dos blocos por proposta aceita (cap 50, um histórico
por discurso, limite 10 discursos). Multi-op = 1 entrada. Reject não registra.
Nova edição após undo descarta redo. Botões Desfazer/Refazer no Header.

## 5. Modos

`rewrite`/`improve` emitem `replace` (prompts distintos: versão integral vs
preservar ideias); `insert` emite `insert after`; `delete` local; `suggest`
texto livre. Não há modo `replace` separado: reescrever/melhorar JÁ são
substituições integrais — documentado para não criar distinção artificial.

## 6. Testes (`npm test`)

23 novos (57 totais; Fases 3+4 intactas): operações/validação/stale (10),
histórico/parser/modos/provider/ContextPack/anti-invenção (13).

## 7. Limitações

- Preview é textual (ANTES/DEPOIS), sem diff por palavra.
- Insert sempre em nível de bloco (before/after), sem intra-bloco.
- `plainText` derivado por strip regex (suficiente para métricas).
- Sem seleção multi-bloco como alvo.

## 8. Impacto futuro

- **Fase 6**: `baseHashes` + `foundBy/scores` do ContextPack alimentam
  verificação; parser já rejeita atribuições via alvo.
- **Fase 7**: trilha training rotulada chega ao prompt de edição.
- **Fase 9**: operações por bloco viabilizam equilíbrio de tempo por ponto.
