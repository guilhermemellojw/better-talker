# Copilot — citações do esboço no contexto (P0+P1)

**Data:** 2026-10-23 · **Base:** v2.7.2 (`28883ff`) + working tree
**Validação:** tablet 25040RP0AL (não usar o A34, ocupado) · **2.201 testes / 0 falhas**

## Problema

O esboço cita `w25.07 22 §§ 8-9`, `it "Gedalias" §4` etc. O encanamento já existia
(`refScopeIds` → `noteScope` → `askScoped`), mas:

1. o `matchEdition` só casava anexos `indexed=1` (37 `failed` no A34);
2. o pack recebia o **ID** da edição, não o **trecho exato** da citação;
3. `contentResolved`/`snippet` alimentavam o card de referência, não o prompt;
4. `enrichedRetrievalQuery` não incluía a ref citada;
5. um PDF sem `page/paragraph` (fatiado por linha) virava “sem texto”.

## O que mudou

| Camada | Mudança |
|---|---|
| `CopilotRepository.citedSources()` | detecta refs da nota+esboço, casa a edição e usa `resolveContent` para extrair o **parágrafo exato** (`page`/`paragraph`); devolve `EvidenceSource` (CONTENT) + `unavailable` |
| `ContextPack` | novo `unavailableRefs` |
| `ContextPacks.fromCandidates()` | `pinned` (citações) entra **antes** do top-K; `unavailableRefs` vai junto |
| `RetrievalRepository.buildPack()` | aceita `pinned`/`unavailableRefs` |
| `CopilotViewModel` | passa as citações ao pack; `enrichedRetrievalQuery` inclui as refs citadas |
| `ChatPromptBuilder.serializePack()` | bloco `REFERÊNCIAS DO ESBOÇO SEM TEXTO NO ACERVO (não citar como texto)` |
| `ImportValidation` (novo) | valida assinatura (PDF/ZIP/EPUB/JWPUB/RTF) antes de indexar; HTML salvo como `.jwpub` falha com mensagem acionável |
| `DownloadHelper` | envia **cookie da WebView** + UA ao DownloadManager |
| `DbProvider.onOpen` | backfill `status='ready' AND indexed=0` (só com passagens) |
| `JwMediaApi` | prefere **JWPUB > EPUB > PDF > RTF** (PDF do jw.org não tem page/paragraph) |
| `RefDetector.matchEdition` | entre duplicatas, prefere JWPUB/EPUB (rank) |

## Validação (tablet)

| Passo | Resultado |
|---|---|
| Nota “Ter esperança” cita `w25.07 22 §§ 8-9`; `w` ausente | ✅ “Não tenho o conteúdo de A Sentinela de julho de 2025 no meu acervo… não posso citar” — sem inventar |
| “Baixar direto” (API oficial) | ✅ `w_T_202507.jwpub` **4 MB**, `ready/indexed=1`, **885 passagens**, page 2-25, paragraph 1-19 |
| Pág. 22 §8-9 no índice | ✅ “Baruque, o secretário do profeta Jeremias…”, “pare de procurar grandes coisas para si…”, “fique satisfeito com a situação atual…” |
| Mesma pergunta com a fonte | ✅ resposta usa o conteúdo real da pág. 22 §8 |
| “Fontes e apoio” | ✅ `w25.07 22 §§ 8-9`, `20 §2`, `24 §§ 13-14`, `Estudo 31 §14` |
| C6 (`Como faço a entonação…`) | ✅ respondeu com princípio prático; sem o template “Ainda não gerei nenhuma parte” |
| C8 (`Gera a introdução completa…`) | ✅ card “Texto para o card / Usar no card” |

**Achado do PDF:** o `w_T_202507.pdf` (baixado antes) rendeu **2.172 fragmentos de linha**,
sem `page/paragraph`; o modelo via só títulos. O JWPUB é o formato correto.

## Pendências

- Validar citação de **livro** (`it "Gedalias" §4`) e de **S-38** no mesmo fluxo (o `it`/S-38
  já estão indexados no tablet; falta uma nota que os cite).
- UX “Fontes do esboço” (painel de status + “baixar todas”) — P2 do plano.
- Os arquivos `ChatRouter.kt`, `GenerationPrompts.kt`, `ChatScreen.kt`, `StageScreen.kt`
  e os testes C6/C8 já estavam modificados no working tree **antes** desta implementação;
  não foram incluídos neste commit (decisão do usuário).
- Release: o build validado é 2.7.2 + estas mudanças; candidato a **v2.7.3**.

---

## Sessão 3 — `it`/S-38 + orçamento (2026-10-23)

### Orçamento 8+4
O comportamento era **(ii)**: `(pinned + ranked).take(8)` descartava o excedente em
silêncio. Corrigido para **(i)**:
- `ContextPack.omittedRefs`;
- `ContextPacks.fromCandidates` mantém as citadas até o limite e manda o excedente
  para `omittedRefs`;
- `serializePack` cria o bloco **“REFERÊNCIAS FORA DO ORÇAMENTO (texto existe, mas
  não coube; não citar como texto)”**;
- teste: 10 citadas → 8 no pack + 2 em `omittedRefs`.

### `it` (livro JWPUB) — ✅
A nota de teste citou `it "Aicão" §1`; o pack subiu de 5 para 6 fontes e a resposta
usou o conteúdo do verbete (Aicão, a redescoberta da Palavra). Na pergunta sobre
“amor”, o modelo identificou o descasamento da citação e sugeriu `it AMOR §5193`.

### S-38 — referência corrigida + parser por parte
- **S-38 = “Instruções para a reunião Nossa Vida e Ministério Cristão”** (WOL
  `1201038`), organizado por **partes**: COMENTÁRIOS INICIAIS, TESOUROS DA PALAVRA
  DE DEUS, FAÇA SEU MELHOR NO MINISTÉRIO, NOSSA VIDA CRISTÃ, COMENTÁRIOS FINAIS.
- **“S-38 lição 3” não existe** (lições são do TH); o teste estava com referência
  inválida. Confirmei na WOL: a página não contém “lição 3”.
- O `SYMBOL_REF_RE` só reconhecia “lição/cap./pág./§ + número”. Adicionado
  `S38_PART_RE` (`S-38 + PARTE`), que grava a parte em `chapter`; o
  `unitSliceOf` acha a seção e o `resolveContent` entrega o trecho.
- **Validado**: “O que o S-38 TESOUROS DA PALAVRA DE DEUS orienta?” → resposta com
  as instruções reais (apostila com tema e 2-3 pontos; discurso por ancião/servo
  ministerial; vídeo do livro; imagens da apostila).

### Estado
- **2.204 testes / 0 falhas**.
- Commits: P0+P1 `2c45394`; overflow `0c6a394`; C6/C8 `574f343`; Stage `c514d24`;
  S-38 por parte (este commit).
- Branch da feature: `feat/copilot-citations` (P0+P1 + overflow + S-38).
- **Release v2.7.3 pendente de decisão** (o build validado no tablet é 2.7.2 + estas
  mudanças; ainda sem tag).
