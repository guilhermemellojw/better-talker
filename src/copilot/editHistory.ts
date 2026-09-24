// Histórico de edições do Copilot — Fase 5 (§10).
// Snapshots dos blocos por proposta aceita (atômica, §11). Rejeitar não
// registra. Nova edição após undo descarta o redo (semântica padrão).

import type { SpeechBlock } from '../types/speech';

const MAX_HISTORY = 50;

function cloneBlocks(blocks: SpeechBlock[]): SpeechBlock[] {
  return blocks.map((b) => ({ ...b }));
}

export class EditHistory {
  private undoStack: SpeechBlock[][] = [];
  private redoStack: SpeechBlock[][] = [];

  get canUndo(): boolean {
    return this.undoStack.length > 0;
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0;
  }

  get depth(): number {
    return this.undoStack.length;
  }

  /** Registra o estado ANTES de aplicar a proposta aceita. */
  push(blocks: SpeechBlock[]): void {
    this.undoStack.push(cloneBlocks(blocks));
    if (this.undoStack.length > MAX_HISTORY) this.undoStack.shift();
    this.redoStack = [];
  }

  /** Desfaz: retorna o snapshot anterior e guarda o atual para redo. */
  undo(current: SpeechBlock[]): SpeechBlock[] | null {
    const prev = this.undoStack.pop();
    if (!prev) return null;
    this.redoStack.push(cloneBlocks(current));
    return prev;
  }

  /** Refaz: retorna o snapshot e o recoloca no undo. */
  redo(current: SpeechBlock[]): SpeechBlock[] | null {
    const next = this.redoStack.pop();
    if (!next) return null;
    this.undoStack.push(cloneBlocks(current));
    return next;
  }

  clear(): void {
    this.undoStack = [];
    this.redoStack = [];
  }
}
