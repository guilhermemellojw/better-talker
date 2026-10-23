# Parser de colagem — formatos aceitos (v2.7.5)

## Formato A — apostila inline (1 parte) — `PastedOutlineAnalyzer.inlineApostila`

```
Tema (N min)
Ponto 1 com ref (w25.07 22 §§ 8-9; Jer. 45:3)
Ponto 2 com ref
...
```

- **1 seção** com o título = `Tema` (sem o `(N min)`).
- **body** = as N linhas (os pontos), preservando as quebras.
- `minutes`/`_draftTotal` = N.
- Guards: não se aplica se houver `Tema:` (S-34 oficial), pontos numerados,
  outra seção temporizada, corpo recuado ou linha sem referência.

## Formato B — esboço completo (N seções)

```
Título da seção A (3 min)
corpo A
Título da seção B (7 min)
corpo B
```

- **N seções**, cada uma com título/minutos e corpo; o caminho antigo
  (`splitTitle` + `candidates` + `bodies`) continua.
- O Formato A **não** captura este caso (guards de tempo/recuo).

## Formato C — S-34 oficial

```
Tema: ...
1. Ponto ...
2. Ponto ...
```

- Vai para o `S34Parser` (formato oficial), **não** para o `PastedOutlineAnalyzer`.
- `S34Detector` continua **não chamado** (dead code); o tipo “Discurso/Demonstração”
  é o toggle da planilha, não inferência.

## Por que o Formato A existia quebrado

Cada frase virava `Candidate` (título) e `bodies` só coletava níveis maiores;
as frases eram nível 0 → 3 seções com corpo vazio → nota de 0 palavras.
O Formato A agora mapeia as frases para o **body** da única seção.
