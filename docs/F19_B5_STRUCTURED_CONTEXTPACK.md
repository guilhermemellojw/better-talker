# F19-B.5 — ContextPack estruturado + cláusula S-34 no prompt

## 1. Novo bloco estrutural

`OutlineStructureContext` / `S34StructureContext` (mesmo modelo nas duas
plataformas):

```text
outlineId, title, objective?
orderedSections[]  (id, order, title, minutes?, isCurrent)
currentSection?    (id, order, title, content,
                    subsections[] (id, order, content, references[]),
                    references[])
focusState         section | document | unknown-section | unmatched-hint
```

Rótulos textuais (não emoji): `[S34]`, `[BIBLE]`, `[PUBLICATION]`,
`[TRAINING]` no bloco de oratória, `[Fonte n]` no conteúdo.

## 2. Prioridade no prompt (§22)

```text
1. brief (histórico + continuidade + mensagem)
2. foco (verificação factual rebaixa treino)
3. --- S-34 (ESTRUTURA DO DISCURSO) ---      ← NEW, primeiro
4. --- FONTES DE CONTEÚDO ---
5. --- ORIENTAÇÕES DE ORATÓRIA ---
6. --- BLOCO ATUAL ---
7. REGRAS DO S-34                             ← NEW
8. Texto do bloco em foco
```

## 3. Ponto atual (§§6-7, 32)

- `SectionFocus` → lista ordenada COMPLETA do documento (o modelo sabe que
  o ponto 2 vem depois do 1 e antes do 3) + detalhe do ponto atual (corpo,
  subpontos, referências) marcado com `<= PONTO ATUAL` e `PONTO ATUAL (2):`.
- `DocumentScope` → objetivo + lista ordenada, sem corpo (limite §21).
- `UnknownSection` / `UnmatchedHint` → `Ponto atual: NÃO IDENTIFICADO ...`
  — nunca se finge foco.
- `NoOutline` → não existe bloco (caminho legado intacto).

A lista ordenada vem do **documento** (`sectionRefs`), não do escopo: sem
isso o modelo perderia a sequência (bug pego pelo teste ponta a ponta).

## 4. Relação com a B.4

Consome direto: `S34StructuralRetriever.Result` → `structuralContextOf`.
Nenhuma regra da B.4 mudou (estados, ordem por `sourceLine`, `matched`
informativo). A B.5 não reimplementa retrieval; só representa.

## 5. CONTENT × TRAINING (§11)

Blocos separados e não intercambiáveis: S-34 (estrutura) → conteúdo
(Bíblia/publicações) → oratória (BE/TH, com aviso "NÃO usar como fatos").
Testes verificam que o bloco S-34 não contém treinamento e vice-versa.

## 6. Regras do prompt (§§12-15, 34-35)

`S34_PROMPT_RULES` (8 regras): fonte estrutural, ordem preservada, sem
inventar pontos, referências presas ao dono, S-34/Bíblia/publicações como
CONTEÚDO, BE/TH só COMO apresentar, criatividade distinguível de fato,
insuficiência literal. Entra **somente** quando há estrutura.

## 7. Comportamento sem S-34 (§31, §29)

Nada muda: mesmos blocos de conteúdo/treino, sem cláusula, sem bloco.
Testado nas duas plataformas.

## 8. Section desconhecida (§32)

Estado preservado: nenhum `CURRENT SECTION` fabricado; o prompt diz que o
foco não foi identificado. Isso evita resposta com falsa precisão.

## 9. Limites de contexto (§21, §37)

- Geral: objetivo + lista ordenada (sem corpos) — sem duplicar.
- Seção: lista ordenada + ponto atual. Corpos de outros pontos não vão.
- O bloco estrutural é a representação canônica; trechos recuperados
  continuam em seu bloco próprio (uma representação + referência
  complementar; nada é enviado duas vezes no mesmo bloco).

## 10. Exemplo completo (fixture)

```text
--- S-34 (ESTRUTURA DO DISCURSO) ---
[S34] Título: Como fortalecer a fé
[S34] Objetivo: Mostrar como a fé pode ser fortalecida por meio da Palavra de Deus.
[S34] Pontos, na ordem do esboço:
[S34]   1. A fé precisa de uma base sólida (4 min)
[S34]   2. A fé cresce quando colocamos em prática o que aprendemos (5 min)  <= PONTO ATUAL
[S34]   3. Continue fortalecendo sua fé (3 min)
[S34] PONTO ATUAL (2): A fé cresce quando colocamos em prática o que aprendemos
[S34]   Corpo do ponto: Leia Tiago 2:17.
[S34]     Consulte a publicação de estudo w24.02, §5.
[S34]   Subponto 1: a) Estudar regularmente
[S34]   Subponto 2: b) Aplicar o que aprendemos
[S34]   [BIBLE] Tiago 2:17. (vinculada ao ponto 2; linha 17)
[S34]   [PUBLICATION] w24.02 (vinculada ao ponto 2; linha 18)
[S34] Fim da estrutura. A ordem acima é a ordem do discurso.
--- FIM DA ESTRUTURA DO S-34 ---
```

## 11. Ajuste de contrato estritamente necessário (§41)

Para a estrutura realmente chegar ao prompt, `LlmRequest` ganhou um campo
OPCIONAL e retrocompatível `structural` nas duas plataformas
(`llmProvider.ts` no web, `LlmProvider.kt` no Android). Sem ele, o bloco
seria montado mas nunca consumido pelo provider. Nenhuma outra mudança em
provider/LLM: `GeminiProvider`/`geminiProvider` só repassam o campo ao
builder de prompt.

## 12. Limitação estrutural desta fatia (IMPORTANTE)

**Nada ainda PRODUZ um S-34 persistido em produção.** O consumidor está
ligado ponta a ponta (Android: `buildTurnFor` → `structuralFor` →
`LlmRequest.structural`; Web: `fetchStructuralContext` no
`CopilotDrawer` → `LlmRequest.structural`), mas o produtor — o hook de
importação que roda `S34Detector` → `S34Parser` →
`S34OutlineRepository.save` ao anexar o arquivo — não foi implementado em
B.1–B.5 (a B.3 declarou explicitamente "integração fica para depois").

Sem esse hook, em runtime o resultado é `NoOutline` e o Copilot segue no
caminho legado (comportamento atual preservado). O pipeline completo está
provado por teste ponta a ponta, não por uso real ainda.

## 13. Fronteira Web documentada

O web não tem vínculo discurso↔attachment (publicações são globais); a
associação usa o título do discurso (`findS34OutlineIdForSpeech`),
conservadora: ambígua/ausente ⇒ `null`. O Android usa o escopo de citações
da nota (`refScope`), que é determinístico.

## 13. Próxima fatia sugerida

F19-B.6: hook de importação (produzir o outline) + testes de aceitação A–H
do enunciado da B; depois disso a inteligência estrutural fica observável
em uso real.
