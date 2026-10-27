# Validação no A34 — C2a (refs com prefixo de página)

**Data:** 2026-10-27
**Branch:** `feat/v2.8.2-c2a` (a partir da main `0174496`)
**APK:** release assinado 2.8.1/26 rebuildado com C2a (mesma assinatura; `-r` preservou os dados)
**Suíte:** **2.533 testes JVM / 0 falhas** · `lintRelease` verde

## Código

- `W_CODE_RE` aceita `p.`, `p.22`, `pág.`, `página`, `pg.` antes da página.
- Mantém `§`/`§§` e ranges.
- `RoomReferenceResolver.resolveByPageParagraph` remove a cauda de página/§
  do `symbol` ao montar o rótulo canônico, evitando
  `w25.07 p. 22 §8 pág. 22 §8`.
- `questionPubs` e dossiê não foram alterados.

## Testes puros

`RefDetectorTest` cobre:

| Entrada | Resultado |
|---|---|
| `w25.07 p. 22 §8` | page=22, paragraph=8 |
| `w25.07 p.22 §8` | page=22, paragraph=8 |
| `w25.07 pág. 22 §8` | page=22, paragraph=8 |
| `w25.07 página 22 §8` | page=22, paragraph=8 |
| `w25.07 pg. 22 §8` | page=22, paragraph=8 |
| `w25.07 p. 22 §§8-9` | page=22, paragraph=8, paragraphEnd=9 |
| `w25.07 p. 22` | page=22, sem § |
| `w25.07 22 §8` | page=22, paragraph=8 (controle) |

`RoomReferenceResolverTest` cobre a integração
`detect → publicationRefOf → resolvePublication → publicationHitOf` e
confirma **1 hit** com o texto do §8 e rótulo canônico
`w25.07 pág. 22 §8`.

## A34 — pergunta com prefixo

Chat limpo, pergunta enviada via ADBKeyboard (por causa do `§`):

> “o que w25.07 p. 22 §8 diz sobre este ponto?”

Log:

```text
ChatRouter: route=GENERAL reason=pergunta de orientação, não geração
CopilotLLM: chat remoto: 1 trecho(s) de publicação da pergunta injetado(s)
CopilotLLM: generation|route=chat|budget=4096|finish_reason=stop|chars=1215|words=216|truncated=false|refs=0|inventory_chars=0
```

A resposta usou **Baruque/secretário de Jeremias** e a orientação do §8,
confirmando que o trecho entrou no prompt.

## A34 — controle sem prefixo

Chat limpo, pergunta:

> “o que diz w25.07 22 §8?”

Log:

```text
ChatRouter: route=GENERAL reason=pergunta de orientação, não geração
CopilotLLM: chat remoto: 1 trecho(s) de publicação da pergunta injetado(s)
CopilotLLM: generation|route=chat|budget=4096|finish_reason=stop|chars=1171|words=202|truncated=false|refs=0|inventory_chars=0
```

Controle passou; sem regressão no caminho sem `p.`.

## Observações

- `refs=0` continua sendo o inventário de fontes da NOTA (S-34 sem fontes
  vinculadas), não o trecho da pergunta; `questionPubs` injetou 1 trecho.
- O teclado foi restaurado para o SwiftKey após o teste.
- O APK C2a é local; o dry-run/tag v2.8.2 só no fim da frente voice P0.
