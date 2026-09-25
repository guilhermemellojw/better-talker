// Timeout de storage — RC (§9/§20): nenhuma operação Dexie trava a UI.
// withStorageTimeout não importa Dexie em si (só tipos), então é seguro em Node.

import { describe, expect, it } from 'vitest';
import { withStorageTimeout, STORAGE_TIMEOUT_MS } from '../../services/db';

describe('withStorageTimeout', () => {
  it('resolve rápido quando a operação responde', async () => {
    await expect(withStorageTimeout(Promise.resolve(42), 't', 1000)).resolves.toBe(42);
  });

  it('rejeita quando a operação trava (IndexedDB pendurado)', async () => {
    const hanging = new Promise<string>(() => {});
    await expect(withStorageTimeout(hanging, 'hang', 50)).rejects.toThrow(/timeout em hang/);
  });

  it('propaga erro real da operação, sem mascarar', async () => {
    await expect(
      withStorageTimeout(Promise.reject(new Error('falha real')), 't', 1000),
    ).rejects.toThrow('falha real');
  });

  it('default de 8s documentado', () => {
    expect(STORAGE_TIMEOUT_MS).toBe(8000);
  });
});
