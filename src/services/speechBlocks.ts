// Atualização de bloco em ÚNICA operação — Fase 12 (P0).
// O bug: BlockEditor.handleInput chamava onBlockChange + onSpeechChange, dois
// setState com a mesma base obsoleta; no batch do React o segundo anulava os
// blocos do primeiro, então digitar nunca persistia (métricas 0, reload perdia).
// Agora: uma única fusão pura (testável, sem DOM) por alteração de bloco.

import type { Speech, SpeechBlock } from '../types/speech';

export interface BlockPatchResult {
  blocks: SpeechBlock[];
  /** Espelho speech-level: bloco editado como h2/p, demais com HTML original. */
  contentHtml: string;
}

export function applyBlockPatch(
  speech: Speech,
  blockId: string,
  patch: Partial<SpeechBlock>,
): BlockPatchResult {
  const updatedBlocks = speech.blocks.map((b) =>
    b.id === blockId ? { ...b, ...patch } : b,
  );
  const edited = updatedBlocks.find((b) => b.id === blockId);
  const contentHtml = edited
    ? updatedBlocks
        .map((b) =>
          b.id === blockId ? `<h2>${b.title}</h2><p>${b.plainText}</p>` : b.contentHtml,
        )
        .join('<hr/>')
    : speech.contentHtml;
  return { blocks: updatedBlocks, contentHtml };
}
