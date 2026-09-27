// Hook de importação S-34 → OutlineDocument persistido — Fase 19-B.6.
//
// Chamado pelo indexador logo após a extração de texto, com a identidade
// persistível da publicação já existente. O parser (B.2) é a única
// autoridade de estrutura; aqui só orquestra detectar → parsear → salvar.
//
// Espelho conceitual de android/.../data/s34/S34ImportHook.kt.
// NÃO baixa publicações: só registra/estrutura/persiste/vincula.

import { isS34 } from './s34Detector';
import { parseS34 } from './s34Parser';
import { saveS34Outline, type S34Db } from './s34Repository';

export type S34ImportOutcome =
  | { kind: 'saved'; outlineId: string; sections: number; references: number }
  | { kind: 'not-s34' }
  | { kind: 'parse-failed'; reason: string };

export interface S34ImportLogger {
  info(msg: string): void;
  warn(msg: string): void;
}

const defaultLogger: S34ImportLogger = {
  info: (m) => console.info('[S34Import]', m),
  warn: (m) => console.warn('[S34Import]', m),
};

/**
 * Executa o hook. Nunca lança: falha de importação não pode derrubar a
 * indexação do acervo. Registra só diagnóstico (sem texto do documento).
 */
export async function onDocumentExtracted(
  db: S34Db,
  attachmentId: string,
  rawText: string,
  logger: S34ImportLogger = defaultLogger,
): Promise<S34ImportOutcome> {
  try {
    if (!isS34(rawText)) return { kind: 'not-s34' };
    const doc = parseS34(rawText);
    if (doc.sections.length === 0) {
      const reason = 'sem pontos reconhecidos';
      logger.warn(`S34 parse failed attachment=${attachmentId} reason=${reason}`);
      return { kind: 'parse-failed', reason };
    }
    await saveS34Outline(db, doc, attachmentId);
    const references = doc.sections.reduce(
      (n, s) => n + s.references.length + s.subsections.reduce((m, sub) => m + sub.references.length, 0),
      0,
    );
    logger.info(
      `S34 detected attachment=${attachmentId} outline=${doc.id} sections=${doc.sections.length} references=${references}`,
    );
    return { kind: 'saved', outlineId: doc.id, sections: doc.sections.length, references };
  } catch (err) {
    const reason = err instanceof Error ? err.name : 'unknown';
    logger.warn(`S34 parse failed attachment=${attachmentId} reason=${reason}`);
    return { kind: 'parse-failed', reason };
  }
}
