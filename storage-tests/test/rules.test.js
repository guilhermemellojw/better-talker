import { readFileSync } from 'node:fs';
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from '@firebase/rules-unit-testing';
import {
  deleteObject,
  getBytes,
  ref,
  updateMetadata,
  uploadBytes,
} from 'firebase/storage';
import { after, before, beforeEach, describe, it } from 'mocha';

const PROJECT_ID = 'demo-better-talker';
const OWNER = 'user-owner';
const OTHER = 'user-other';
const PATH = `users/${OWNER}/images/note-1/img-1.png`;
const IMAGE = new Uint8Array([137, 80, 78, 71]);

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    storage: {
      host: '127.0.0.1',
      port: 9199,
      rules: readFileSync(new URL('../../storage.rules', import.meta.url), 'utf8'),
    },
  });
});

after(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearStorage();
});

const ownerStorage = () => testEnv.authenticatedContext(OWNER).storage();
const otherStorage = () => testEnv.authenticatedContext(OTHER).storage();
const anonStorage = () => testEnv.unauthenticatedContext().storage();

const upload = (storage, path = PATH, contentType = 'image/png', bytes = IMAGE) =>
  uploadBytes(ref(storage, path), bytes, { contentType });

describe('storage de imagens', () => {
  it('dono sobe imagem', async () => {
    await assertSucceeds(upload(ownerStorage()));
    await assertSucceeds(getBytes(ref(ownerStorage(), PATH)));
  });

  it('outro usuário não lê nem grava', async () => {
    await assertSucceeds(upload(ownerStorage()));
    await assertFails(getBytes(ref(otherStorage(), PATH)));
    await assertFails(upload(otherStorage()));
  });

  it('anônimo é negado', async () => {
    await assertFails(upload(anonStorage()));
  });

  it('content-type não-imagem é negado', async () => {
    await assertFails(upload(ownerStorage(), PATH, 'text/plain'));
  });

  it('objeto acima de 10 MB é negado', async () => {
    const big = new Uint8Array(10 * 1024 * 1024 + 1);
    await assertFails(upload(ownerStorage(), PATH, 'image/png', big));
  });

  it('update para content-type inválido é negado', async () => {
    await assertSucceeds(upload(ownerStorage()));
    await assertFails(
      updateMetadata(ref(ownerStorage(), PATH), { contentType: 'text/plain' }),
    );
  });

  it('dono apaga; outro não apaga', async () => {
    await assertSucceeds(upload(ownerStorage()));
    await assertFails(deleteObject(ref(otherStorage(), PATH)));
    await assertSucceeds(deleteObject(ref(ownerStorage(), PATH)));
  });
});
