# F19-B.6 — Hook de importação + aceitação A–H

## 1. Ponto do hook

O hook roda **depois da extração de texto e da criação do attachment**, que
é o primeiro momento em que existem as duas coisas de que ele precisa:
texto bruto e identidade persistível.

| Plataforma | Arquivo | Ponto exato |
|---|---|---|
| Android | `IndexPublicationWorker.kt` | após `raw` extraído e checado não-vazio, antes de gravar passages |
| Web | `libraryIndexer.ts` (`indexPublication`) | após `indexPassages` e o `update(indexed:true)`, com `rawText` dos MESMOS units |

Nada muda no caminho de documento comum: `NotS34` simplesmente segue.

## 2. Associação attachment → outline

`attachment.id` / `publication.id` é a identidade de origem
(`sourceAttachmentId`), como a B.3 já previa. **Não** se usa título, nome de
arquivo, texto de bloco nem similaridade. A heurística por título da B.5
(`findS34OutlineIdForSpeech`) permanece apenas como fallback de leitura web
para discursos antigos; a produção agora grava pelo id.

## 3. Fluxo import → parse → persist

```text
arquivo → attachment.id + texto extraído
   ↓ S34Detector.isS34 (puro, sem LLM)
   ├── não é S-34 → NotS34 → fluxo legado intacto
   └── é S-34
        ↓ S34Parser.parseS34 (autoridade única de estrutura)
        ├── 0 seções → ParseFailed (estado explícito, NADA persistido)
        └── ≥1 seção → S34OutlineRepository.save(doc, attachmentId)
                        ↓
                   OutlineDocument (sections/subsections/references)
```

O hook nunca lança (exceto cancelamento): falha de importação não pode
derrubar a indexação do acervo.

## 4. Idempotência

`save` resolve pelo `sourceAttachmentId` (B.3): reimportar o mesmo
attachment substitui a estrutura. Testado: 1 outline, 3 seções, 5
referências, mesmo após reexecutar o hook.

## 5. Atualização (arquivo mudou)

v1 (3 pontos) → v2 (4 pontos): a estrutura persistida é a v2, sem resíduos
de seções/subpontos/referências removidos (testado nas duas plataformas).

## 6. Remoção

`deletePublication` (web) e `LibraryRepository.delete` /
`NotesRepository.deleteForever` (Android) já chamam o cascade da B.3.
Teste completo: importar → outline existe → remover attachment → outline
desaparece → `NoOutline`.

## 7. Aceitação A–H (camada determinística — a prova principal)

| # | Pergunta | O que o contexto entrega | Resultado |
|---|---|---|---|
| A | Qual é o objetivo? | `objective` explícito | PASS |
| B | Quais são os pontos principais? | lista 1→2→3 em ordem documental | PASS |
| C | Qual é a sequência? | `order` da estrutura (nunca score) | PASS |
| D | Textos bíblicos do ponto 2 | Tiago 2:17 só em `sec-2` | PASS |
| E | Publicações do ponto 3 | `w24.03` só em `sec-3` (fixture sintética) | PASS |
| F | Desenvolver o ponto 2 sem sair | objetivo + posição + corpo + subpontos + refs + vizinhos | PASS |
| G | Como introduzir? | objetivo + primeiro ponto + BE/TH separado | PASS |
| H | Como concluir? | objetivo + último ponto + BE/TH separado | PASS |

Nenhum objeto `introduction`/`conclusion` é criado (a camada oratória virá
depois, usando esses insumos).

## 8. Web × Android

Mesma fixture sintética, mesmos estados, mesmas projeções
(outlineId/title/objective/sections/subsections/references/currentSection/
provenance). O web importa via EPUB sintético pela função real da
Biblioteca (`indexPublication`); o Android pelo hook exercitado como o
worker o chama (texto extraído + id).

Correção necessária no caminho web: `stripHtmlTags` colapsa TODAS as
quebras de linha, e o S-34 é orientado a linhas — o parser não acharia
pontos em EPUB real. Adicionado `stripHtmlToLines` usado **só** como texto
bruto do hook; o índice de passages continua com o texto achatado
(nenhuma mudança de busca, nenhum schema novo).

## 9. Validação física

**physical validation unavailable.** O aparelho SM-A346M usado nas fases
anteriores não estava conectado no momento desta fase (`adb devices`
vazio, anúncio mDNS ausente) e não há emulador executável no ambiente
(usuário fora do grupo `kvm`). Nada foi executado no dispositivo nem
simulado como se tivesse sido.

## 10. Limitações restantes

- Web: acervo aceita EPUB/PDF; o hook roda sobre o texto extraído deles.
  Documentos importados por OUTROS caminhos (ex.: colar texto no esboço)
  não passam por este hook — o esboço colado já tem fluxo próprio
  (`PasteOutlineAnalyzer`), fora do escopo desta fase.
- Android: o hook roda no `IndexPublicationWorker`; arquivos que falham a
  extração não tentam o parser (correto: sem texto não há estrutura).
- Nada é baixado automaticamente: refs de publicação são estruturadas e
  vinculadas ao ponto, e a resolução/download continua manual (F8).
- Sem LLM em nenhum ponto do hook (detecção, parsing e persistência são
  determinísticos).
- `introduction`/`conclusion` continuam NÃO persistidos, por decisão.

## 11. Como validar em uso real

1. Importar um S-34 (PDF/DOCX/EPUB) pela Biblioteca.
2. `logcat` deve mostrar uma linha `S34Import`: attachment, outline,
   quantidade de seções e de referências (sem texto do documento).
3. Abrir o Copilot e perguntar pelo objetivo/pontos: o prompt passa a levar
   o bloco `--- S-34 (ESTRUTURA DO DISCURSO) ---` com ponto atual e
   referências vinculadas.
4. Documento comum: nenhuma linha `S34Import detected` e nenhum bloco
   estrutural — comportamento legado idêntico.
