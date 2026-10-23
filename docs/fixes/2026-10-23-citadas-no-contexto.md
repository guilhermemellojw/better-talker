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
