import { readFileSync } from 'node:fs';
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, serverTimestamp } from 'firebase/firestore';
import { after, before, beforeEach, describe, it } from 'mocha';

const PROJECT_ID = 'demo-better-talker';
const OWNER = 'user-owner';
const OTHER = 'user-other';

let testEnv;

const validNote = () => ({
  title: 'Nota',
  mdText: 'conteúdo',
  richHtml: '<p>conteúdo</p>',
  pinned: false,
  trashed: false,
  colorArgb: 0,
  createdAt: 1,
  updatedAt: 1,
});

const validFolder = () => ({ name: 'Pasta', colorArgb: 0, createdAt: 1 });

const validSection = () => ({
  noteId: 'n1',
  order: 0,
  role: 'BODY',
  title: 'Ponto',
  minutes: 5,
  contentHtml: '<p>x</p>',
  createdAt: 1,
  updatedAt: 1,
});

const validSubPoint = () => ({
  sectionId: 's1',
  order: 0,
  outlineText: 'a) ideia',
  developedHtml: '<p>x</p>',
  createdAt: 1,
  updatedAt: 1,
});

const validAttachment = () => ({
  fileName: 'w_T_20241215.pdf',
  kind: 'pdf',
  sizeBytes: 123,
  addedAt: 1,
});

const validOutline = () => ({
  noteId: 'n1',
  fileName: 'S-34.jwpub',
  title: 'Esboço',
  sectionsJson: '[]',
  refsJson: '[]',
  createdAt: 1,
  updatedAt: 1,
});

const validS34Outline = () => ({
  sourceAttachmentId: 'a1',
  symbol: 's34',
  title: 'S-34',
  headerLinesJson: '[]',
  createdAt: 1,
  updatedAt: 1,
  parserVersion: 2,
});

const validS34Section = () => ({
  outlineId: 'o1',
  order: 0,
  title: 'Ponto',
  content: 'conteúdo',
  minutes: 4,
  sourceLine: 1,
});

const validS34Subsection = () => ({
  sectionId: 's1',
  order: 0,
  content: 'subconteúdo',
  sourceLine: 1,
});

const validS34Reference = () => ({
  outlineId: 'o1',
  order: 0,
  type: 'BIBLE',
  rawText: 'João 3:16',
  normalizedReference: 'joao 3 16',
  sourceLine: 1,
  chapter: 3,
  verse: 16,
});

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8'),
    },
  });
});

after(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

const ownerDb = () => testEnv.authenticatedContext(OWNER).firestore();
const otherDb = () => testEnv.authenticatedContext(OTHER).firestore();
const anonDb = () => testEnv.unauthenticatedContext().firestore();

describe('notes', () => {
  it('dono grava nota válida', async () => {
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/notes/n1`), validNote()),
    );
  });

  it('outro usuário não lê nem grava', async () => {
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/notes/n1`), validNote()),
    );
    await assertFails(getDoc(doc(otherDb(), `users/${OWNER}/notes/n1`)));
    await assertFails(
      setDoc(doc(otherDb(), `users/${OWNER}/notes/n1`), validNote()),
    );
  });

  it('anônimo é negado', async () => {
    await assertFails(
      setDoc(doc(anonDb(), `users/${OWNER}/notes/n1`), validNote()),
    );
  });

  it('sem título é negado', async () => {
    const n = validNote();
    delete n.title;
    await assertFails(setDoc(doc(ownerDb(), `users/${OWNER}/notes/n1`), n));
  });

  it('mdText de tipo errado é negado', async () => {
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/notes/n1`), {
        ...validNote(),
        mdText: 42,
      }),
    );
  });

  it('mdText acima do limite é negado', async () => {
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/notes/n1`), {
        ...validNote(),
        mdText: 'x'.repeat(900001),
      }),
    );
  });

  it('schemaVersion diferente de 1 é negado', async () => {
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/notes/n1`), {
        ...validNote(),
        schemaVersion: 2,
      }),
    );
  });
});

describe('folders/sections/subPoints/attachments/outlines', () => {
  it('folder válida', async () => {
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/folders/f1`), validFolder()),
    );
  });

  it('folder sem nome é negada', async () => {
    const f = validFolder();
    delete f.name;
    await assertFails(setDoc(doc(ownerDb(), `users/${OWNER}/folders/f1`), f));
  });

  it('section válida', async () => {
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/sections/s1`), validSection()),
    );
  });

  it('section sem noteId é negada', async () => {
    const s = validSection();
    delete s.noteId;
    await assertFails(setDoc(doc(ownerDb(), `users/${OWNER}/sections/s1`), s));
  });

  it('subPoint válido', async () => {
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/subPoints/sp1`), validSubPoint()),
    );
  });

  it('subPoint com order string é negado', async () => {
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/subPoints/sp1`), {
        ...validSubPoint(),
        order: '0',
      }),
    );
  });

  it('attachment válido', async () => {
    await assertSucceeds(
      setDoc(
        doc(ownerDb(), `users/${OWNER}/attachments/a1`),
        validAttachment(),
      ),
    );
  });

  it('attachment com sizeBytes string é negado', async () => {
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/attachments/a1`), {
        ...validAttachment(),
        sizeBytes: '123',
      }),
    );
  });

  it('outline válido', async () => {
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/outlines/o1`), validOutline()),
    );
  });
});

describe('S-34 flat', () => {
  it('dono grava outline/seção/sub/ref válidos', async () => {
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/s34Outlines/o1`), validS34Outline()),
    );
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/s34Sections/s1`), validS34Section()),
    );
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/s34Subsections/sub1`), validS34Subsection()),
    );
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/s34References/r1`), validS34Reference()),
    );
  });

  it('outro usuário não lê nem grava S-34', async () => {
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/s34Outlines/o1`), validS34Outline()),
    );
    await assertFails(getDoc(doc(otherDb(), `users/${OWNER}/s34Outlines/o1`)));
    await assertFails(
      setDoc(doc(otherDb(), `users/${OWNER}/s34Outlines/o1`), validS34Outline()),
    );
  });

  it('anônimo é negado', async () => {
    await assertFails(
      setDoc(doc(anonDb(), `users/${OWNER}/s34Outlines/o1`), validS34Outline()),
    );
  });

  it('outline sem fonte é negado', async () => {
    const o = validS34Outline();
    delete o.sourceAttachmentId;
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/s34Outlines/o1`), o),
    );
  });

  it('referência com capítulo string é negada', async () => {
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/s34References/r1`), {
        ...validS34Reference(),
        chapter: '3',
      }),
    );
  });

  it('conteúdo acima do limite é negado', async () => {
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/s34Sections/s1`), {
        ...validS34Section(),
        content: 'x'.repeat(900001),
      }),
    );
  });
});

describe('tombstones e clock', () => {
  it('tombstone de tipo conhecido é aceito', async () => {
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/tombstones/n1`), {
        type: 'note',
        deletedAt: 1,
      }),
    );
  });

  it('tombstone de tipo desconhecido é negado', async () => {
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/tombstones/x`), {
        type: 'password',
        deletedAt: 1,
      }),
    );
  });

  it('clock só aceita o campo at', async () => {
    await assertSucceeds(
      setDoc(doc(ownerDb(), `users/${OWNER}/_sync/clock`), {
        at: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/_sync/clock`), {
        at: serverTimestamp(),
        extra: true,
      }),
    );
  });
});

describe('fail-closed', () => {
  it('coleção desconhecida é negada', async () => {
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OWNER}/secrets/x`), { a: 1 }),
    );
  });

  it('árvore de outro usuário é negada', async () => {
    await assertFails(
      setDoc(doc(ownerDb(), `users/${OTHER}/notes/n1`), validNote()),
    );
  });
});
