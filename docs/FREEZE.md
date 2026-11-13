# Itens congelados / decisões pendentes

**Atualizado:** 2026-11-13 · **Release público:** v6.0.3/77 (tag `v6.0.3`) · **Baseline anterior:** v6.0.2/75 (tag `v6.0.2`) · **Fase 2:** concluída (`506f813`) · **Fase 3:** concluída (`ebdcd92`) · **UX:** concluído (`5e5e1fa`) · **Confiança:** concluída (`ae041b4`) · **Agrupamento:** concluído (`93c7b02`) · **Refs unificadas:** concluído (`c53c0b7`) · **Atalho ✨:** concluído (`fe63742`) · **T3-A:** concluído (`83a76e1`) · **Geração longa:** concluída (`1485b2d`) · **C1 — orçamentos:** concluído (`938c0f7`) · **C2a — prefixo de página:** concluído (`41c546d`) · **P0 voz:** concluído (`8ac6c0e`) · **A1/A2:** concluído (2.8.1/26) · **Release v2.8.2:** em **draft** (`4f4efa1c…dbd5a5`) até o teste de restauração da keystore em outra máquina · **Release v2.8.1:** em **draft** como checkpoint (`1397ca64…a93133`)

> **2026-11-13 — v6.0.3/77 (placeholders duplicados de anexos):**
> tag `v6.0.3` em `2e3cbf7`; branch `fix/attachment-placeholder-dedupe`
> (`d8c307e`, `0b5455c`); CI branch `43469124918` e main `43473386376`
> verdes; release workflow `43476977271`; **3.330 testes JVM / 0**, lint
> 0/52. P0 esconde `missing`/`failed` já resolvidos por sha256, publicação
> (mesmo tipo) ou nome; P1 reusa o placeholder no import preservando
> id/vínculo; P2 envia/lê `sha256` no sync e faz backfill sem sobrescrever
> hash remoto divergente. Smoke A34 debug 75 com o patch: “Precisa de
> atenção” caiu de **275 para 247** e `w_T_20080615.jwpub` (edição sem
> arquivo pronto) continuou visível. APK público **77/6.0.3** SHA-256
> `82d598928f5cfc0d559f6f027e8879ca8f13fbbff330440dc47b708cc03e3636`.
> Beta **6.0.2-syncbeta/76** permanece como artifact anterior. P3
> (reconciliar ids por conteúdo no sync) fica para o 6.1.

> **2026-11-13 — v6.0.2/75 (integridade S-34 → seção da nota):**
> tag `v6.0.2` em `88369ba`; main `88369ba`; CI main `43421170661`;
> release workflow `43421213768`; **3.304 testes JVM / 0**, lint 0/52.
> `generateOratory` resolvia `sec-N` como UUID e caía no foco antigo; agora
> um resolvedor puro mapeia role/id/título/ordem e o foco explícito do ponto
> é aplicado antes do turno. Smoke A34 (debug 75): “Desenvolva o ponto 2”
> → Tópico 3/7 e `cardTargetLabel="COMO A VIDA ETERNA FOI PERDIDA"`
> (order 2); conclusão → `cardTargetLabel="Conclusão"`; “ponto 9” honesto
> sem card. APK público **75/6.0.2** SHA-256
> `73b30523e02d73de02a9140e58f9ff2f6c1d190a848271d71fe79624ba35d9a4`.
> A **6.0.1/73** fica no histórico, substituída. Beta **6.0.2-syncbeta/76**
> é artifact separado (`workflow_dispatch`, run `43427168035`, SHA-256
> `54cc85be…d428`), sem tag/página de release. A34 e tablet no público 75
> validados; reader/sync do tablet OK.
> **Smoke dedicado nos dois debug 75 (mesma conta):** nota temporária (#35)
> com 7 seções, S-34 (1 outline/40 refs) e imagem; a edição do título no
> tablet voltou para a A34 com o mesmo `updatedAt`; a exclusão definitiva
> gerou 2 tombstones e removeu nota, seções, imagem, anexo e S-34 nos dois
> aparelhos. Tablet público (outra conta) intacto: **12 notas, 15 anexos,
> 54 seções** no backup. Sync da A34 debug desligado ao final
> (`sync_beta_enabled=false`, persistido após reiniciar).

> **2026-11-12 — v6.0.1/73 (gate 6.0b; 6.0.0 substituída):**
> tag `v6.0.1` em `4d0d1e5`; main `990c8d3`; release workflow `43281592957`;
> CI da branch `43277964309` e da main `43281540579` verdes; **3.298 testes
> JVM / 0**, lint 0 errors / 52 warnings de baseline.
> A 6.0.1 corrige o sheet automático do 6.0: `upgrade_v6_seen` era gravado
> antes de `showV6UpgradeSheet=true`; como `seen` é chave do próprio
> `LaunchedEffect`, a coroutine era cancelada e o sheet não abria. Agora mostra
> primeiro e grava depois; a decisão do 5.0 fecha antes do gate do 6.0.
> **A34:** 6.0.1/73 instalado por cima do 71; primeiro lançamento 5.0→6.0;
> “Agora não” não reabre; Ajuda manual OK; backup pós DB v26 com as mesmas
> contagens e o mesmo `dataSha256` `9be615a2…99e4be`.
> APK público SHA-256
> `e4b72c9b92456fee4eed40fb45147a5134a1ebb3919c4671d61c8563f04ed60a`
> no GitHub Release. A **6.0.0/71** fica no histórico, marcada como
> substituída.
> O beta **6.0.1-syncbeta/74** é **artifact da run `43285272570`**, sem tag e
> sem página de release; SHA-256
> `b9e79e2f737ca81522072f15ed9c344f8a8929bf6d02f1976de0f4b56c4a8f5e`.
> Não abrir 6.0.2 por cosmética; backlog 6.1: 5.7 opcional e sync de chat.

> **2026-11-11 — 5.6b interno (GlobalLibraryTool + citação + reader):**
> Biblioteca sob demanda no Assistente, sem pré-carregar o acervo. Resolve
> publicação/edição + unidade, status READY/INDEXING/MISSING/AMBIGUOUS/
> NO_MATCH/TRAINING_ONLY, retrieval restrito à publicação (4 fontes) com
> fallback para o conteúdo geral. Prompt com FONTES DA BIBLIOTECA e [Fonte N];
> contrato pós-geração valida citações/recusa falsa, faz retry e fallback
> local. Chip “Fontes (N)”, lista de fontes e reader da unidade (TTS, copiar,
> limite); be/th como TRAINING e “Orientação de oratória”. JWPUB de livro sem
> CAPÍTULO explícito cai em CHAPTER pelo catálogo. **3.246 testes JVM**; smoke
> no tablet com artigo 1 da Sentinela N.º 5/2017, reader/TTS, missing,
> ambíguo, be/th e NoMatch. CI da branch `43007000859` e da main
> `43008718647` verdes; merge `94ca24b`. Sem schema, bump, tag ou release.

> **2026-11-11 — 5.6a interno (PublicationUnitIndex):** DB **v26** com
> `publication_units` e migração aditiva v25→v26. Builder puro agrupa
> `PassageEntity.section` em runs contíguos, reconhece PARTE/CAPÍTULO/
> ARTIGO/LIÇÃO/APÊNDICE/SEÇÃO, monta hierarquia de PART e guarda
> `startOrd`/`endOrd`/páginas; revista/it/rsg viram ARTICLE, lmd/th LESSON,
> livro comum CHAPTER e EPUB/PDF/DOCX DOCUMENTO; NWT fora. O indexador grava
> após as passages e o backfill sob demanda não reextrai o JWPUB. **3.216
> testes JVM**; migração validada no tablet e JWPUB reindexado com 25 unidades
> contíguas (0..561). Sem UI, bump, tag ou release.

> **2026-11-10 — 5.5b interno (Mapa mental + Assistente mediador):** UI
> “Tribuna” virou **Mapa mental (antiga Tribuna)** no Palco, editor, pré-voo,
> ajuda e docs; código `Tribuna*` mantido. Lápis 48dp no card do Palco
> (“Editar mapa mental”), status pronto/incompleto/sem mapa, “Gerar/Completar
> mapa mental”; demo sem mapa/lápis. Assistente: menu Biblioteca/Preparo/
> Treino/Palco e intenções locais (“abrir biblioteca”, “meus esboços”,
> “preparar”, “treinar”, “apresentar”); “Abrir nota” vai ao Preparo. Sem
> schema, bump, tag ou release; **3.196 testes JVM**; smoke final no tablet
> (lápis/status do mapa, menu e intenções locais na Conversa geral, create
> sheet nos quatro caminhos e TTS). Branch CI `42931869191` verde; merge
> `c434b33` na main.

> **2026-11-10 — 5.5a interno (navegação em 5 abas + Meus esboços + Preparo):**
> barra global Assistente · Biblioteca · Preparo · Treino · Palco. Meus
> esboços é uma tela única com intenção (assistant/prepare/train/present/
> manage) e botão Gerenciar/Escolher; no modo escolha, FAB, pastas, lixeira e
> menu do card somem. Biblioteca e Preparo são raízes de aba; linkNote e
> editor mantêm back, Preparo root não. Create sheet é único para Assistente/
> Meus esboços/onboarding; o Assistente não cria nota em branco. “Trocar
> nota” virou “Meus esboços”; menu do editor ganhou “Mapa mental”. Sem schema,
> bump, tag ou release; **3.195 testes JVM**; smoke A34 de seleção/
> gerenciamento, Preparo root, Biblioteca raiz/link, criação e rotação.

> **2026-11-10 — 5.4/5.4.1/5.4.2 interno (backup e restore de imagens opt-in):**
> o APK público passa a incluir Firebase **Auth + Storage** apenas para
> imagens; Firestore/sync de notas continua exclusivo do `syncBeta`. Login é
> opt-in: sem conta, nada sobe. Envio automático ao anexar, WebP (GIF ≤ 2 MB
> mantém animação), limite de 10 MB, estado por imagem, retry e **restore**
> com `Baixar imagens` (metadata, tamanho/sha256, fallback por caminho).
> Banco **v25** (`image_backups` com uid e sem FK para a fila `deleted`),
> rules `users/{uid}/images/...` com MIME e limite de 10 MB, sem
> bump/tag/release. O restore local do ZIP continua **só notas** (imagens não
> entram no ZIP); o Palco segue offline.
> **Smoke A34 2026-11-10:** login Google, 2 imagens WebP, anexo novo
> automático, GIF ≤ 2 MB como `image/gif`, remoção remota, **403 sem auth**,
> restore de 2 imagens após apagar os arquivos locais e S-34 PDF/DOCX pela
> Home. 5.4.2: botão público **Apagar backup de imagens** (limpa
> `image_backups` e pausa o envio), restore atômico com `.part`/sha/tamanho
> e A/B no A34 (B viu 0; wipe de B não tocou no A). **3.194 testes JVM** +
> 7 testes de rules Storage.

> **2026-11-10 — v5.3.0/69 (Palco do orador + imagens só em discurso):**
> branch `feat/v5.3-stage-speaker` (`9805ff4`, `c42a9ce`, `f8f5d8b`,
> `d281334`, release `e9550ad`); merge `--no-ff` na main `6b3d45f`; tag
> `v5.3.0` em `e9550ad`; release workflow `42788709525` verde; CI da main
> `42788699498` verde. **3.139 testes JVM / 0**, lint 0 errors / 48 warnings
> idênticos ao baseline (o registro anterior contava 47), DB **v23**, sem
> schema novo e sem Firebase no público.
> Palco: roteiro completo da demo com narração e turno atual,
> `Anterior/Próximo`, modo “só turno atual” e `DemoScript` compartilhado com a
> Tribuna. Imagens: só em discursos; demo esconde “Imagens da nota”, chip,
> “Imagens da seção” e sheet no editor, a tira na Tribuna, as imagens/checagem
> no pré-voo e no Palco; discurso mantém cue `IMAGE` com número/legenda,
> thumbnail, tela cheia, Back com timer contínuo e “Sem imagem”.
> **A34 validou o APK release 69/5.3 instalado por cima do público 5.2.1/67,
> com dados preservados:** demo sem imagens/Lembretes, roteiro completo e
> “só turno atual”; discurso com imagem no editor/Tribuna/pré-voo/Palco,
> thumbnail, tela cheia e timer; TNM (`Jeremias 52`, `51-52`, `52:2` e
> overlay). Para o smoke, o A34 público recebeu uma nota demo e uma
> imagem/cue de teste; são dados de teste, não parte do release. APK público
> SHA-256
> `c212a38763b72c572f6c1d1dceaad0e6316d7da05cadfeb22557dd050e3526cc`.
> Beta 70 via run `42791032717`, SHA-256
> `221eea8120d04eb15014cf128d8f6adb5a7e329184dade62fa48be8986a445fe`
> (artifact `13751599723`, sem tag). As tags `v5.2.0`/`v5.2.1` seguem
> preservadas; o baseline anterior era 5.2.1/67. TalkBack e gate dos amigos
> continuam fora do 5.3.

> **2026-11-09 — v5.2.1/67 (leitor de capítulo):** `detectBibleChapters`
> reconhece capítulo/faixa (`Jeremias 52`, `Jeremias 51-52`, `Salmo 23`,
> `1 João 4`), rejeita `Livro Cap:Versículo`, valida o livro e preserva Jó ×
> João. `BiblePassageReader` aceita capítulos, busca por prefixo
> (`findByRefPrefixIn`), ordena/deduplica, mantém teto de 200 e status
> honesto; `Jeremias 52:2` continua versículo. Sheet/overlay inalterados;
> `resolveBible` do dossiê intocado. Branch `fix/v5.2.1-chapter-reader`
> (`c5b7c1c`), CI `42678698697` verde, **3.123 testes JVM / 0**, lint 47
> warnings. Smoke tablet: Jeremias 52, 51-52 e 52:2 na Tribuna e no Palco.
> Sem TNM coberto por unitário; A34 pendente para repetir (não bloqueia o
> patch); TalkBack no gate. Tag `v5.2.0` não foi movida. Tag `v5.2.1`
> (`a156793`); release workflow `42686889163` verde; APK público 67/5.2.1
> com SHA-256
> `265b0bb8e6df871eae0450218f3b55ca5607d059fa39fe8be0e38559020c2c8a`.
> Beta 68 via run `42688815349`, SHA-256
> `4a5e98d0e9d895de8b4ab129a46d4e6234f804f9ea5b28cf1b1de165bc27f6e6`.
> Link: https://github.com/guilhermemellojw/better-talker/releases/tag/v5.2.1.
> A34 repetiu Jeremias 52/51-52/52:2 e o overlay em 2026-11-10, junto com o
> smoke do 5.3 (ver entrada acima).

> **2026-11-09 — v5.2/65 (Leitor TNM):** `BiblePassageReader` próprio com
> refs livres/faixas/listas, TNM base (`baseReady("nwt")`) e fallback para
> Bíblia indexada; status RESOLVED/PARTIAL/UNRESOLVED/MISSING_CORPUS, ordem,
> dedupe, grupos e teto de 200 com aviso. `PassageDao.findByRefIn`/
> `findByRefsIn` sem schema novo; DB continua **v23**. Sheet na Tribuna e
> Dialog full-screen no Palco para SCRIPTURE/READING e texto-chave, com
> versículos numerados, seleção, TTS sem números, Copiar e Abrir Biblioteca
> sem TNM. O overlay bloqueia toques/swipes do Palco; Back fecha e o timer
> continua. Branch `feat/v5.2-tnm-reader` (`67bac8c`, bump `5d70f8e`, fix de
> teste `71688a0`, bloqueio `2bcd0eb`); CI `42657585498` verde; **3.111
> testes JVM / 0**, lint 47 warnings pré-existentes. Smoke A34: Ezequiel
> 1:16-18, Gênesis 1:26,31, Salmos 23:1-6, João 3:16, parcial, unresolved,
> TTS/Copiar e overlay do Palco com timer/retorno. O bloqueio de toques/swipes
> do overlay (Dialog full-screen) foi validado no A34: cabeçalho/fundo/Anterior/
> Mapa não fecharam nem trocaram seção, swipe não avançou, só Fechar/Back
> fecharam; timer 06:11 → 07:13 e seção/foco 2/6 e 2/7 preservados. Sem TNM
> coberto por teste unitário; TalkBack segue no gate dos amigos. `resolveBible` do dossiê
> continua com a dívida do primeiro versículo. Tag `v5.2.0` (`ec4ef7d`);
> release workflow `42669009800` verde; APK público 65/5.2 com SHA-256
> `9bb4f9e28969c7e8a42d4869c572e2a813a08433b3aa3e1f103551afac1f82c2`
> no GitHub Release: https://github.com/guilhermemellojw/better-talker/releases/tag/v5.2.0.
> Beta 66/5.2-syncbeta é artifact separado, sem tag: run `42672619673`,
> SHA-256 `2ff15f2e0368c0d5c67b63d17c6b2e3a6b30ba3b4e1c8d826f8d617b66797281`.

> **2026-11-09 — v5.1/63 (Assistente + Tribuna 2.1):** Assistente com top
> bar de contexto (`Assistente ⌄` / `nota ⌄` + 💬 ❓ ⚙, sem ⋮), última fala
> completa no topo, rodapé só com PTT e ⚙ movido de Esboços para o Assistente;
> walkie com picker, Conversa geral e texto completo; Tribuna 2.1 com 15
> lembretes, edição completa, `cueSheetVersion` 0/2, qualidade com motivos e
> detalhe por nota/seção + “Usar padrão da nota”; Palco com Mapa/Foco e foco
> por seção persistidos. Sem schema novo; DB continua **v23**. Branch
> `feat/v5.1-tribuna-cues` (`45e15e6` + `f496d74` + `f096f3f`), CI
> `42599933855` verde, **3.089 testes JVM / 0**, lint release 0 errors e 49
> warnings pré-existentes. Smoke A34: home/walkie, menus, picker, Conversa
> geral, candidatos/Sim-Não, chat, PTT, Tribuna com override/limpeza e Palco
> persistente (Mapa/Foco, foco 3/6 e restauração após reinício).
> Pendências: **TalkBack audível**, tema claro/escuro, PTT humano pt-BR e teste
> real continuam; gate dos amigos segue fechado (faltam 5.2, 5.3 e 5.4).
> Tag `v5.1.0` (`8012d55`); release workflow `42611722691` verde; APK público
> 63/5.1 com SHA-256
> `31a4ca65709828026aa70dfd305a9cb206cfffe860efe59adf479aa1ee2a9686`.
> Beta 64 via run `42613799443`, SHA-256
> `26ce448d5dd6af24ae903d8657e2c4cf9e830b764bb1c960010d8c327ff77ba0`.
> Link: https://github.com/guilhermemellojw/better-talker/releases/tag/v5.1.0.
> Smoke final no A34 com o APK release 63/5.1 instalado por cima do 5.0.1/61:
> dados preservados; home/walkie, menus, picker, Conversa geral,
> candidatos/Sim-Não, chat, PTT/TTS, Tribuna (override/limpar,
> adicionar/remover/Desfazer, imagem com/sem `imageId`) e Palco
> (Mapa/Foco, foco por seção, rotação, sair/voltar). A base pública não tinha
> cápsula legada; legado → v2 validado no debug e em testes.

> **2026-11-08 — v5.0.1/61 (resgate do “Novidades”):** o sheet de upgrade
> não é mais consumido ao exibir; `upgrade_v5_dismissed` é a fonte de verdade.
> Quem ficou em `UPGRADE_SEEN` sem dismiss recebe o “Novidades” na próxima
> abertura. Processo morto antes do dismiss → reaparece; depois do dismiss →
> não repete. “Novidades do 5.0” também abre pela **Ajuda** sem resetar
> onboarding/replay. Sem schema novo; DB continua **v23**. Branch
> `fix/v5.0.1-upgrade-notice` (`3e5e5d9`), CI `42521643171` verde,
> **3.042 testes JVM / 0**. A34 debug partiu de `UPGRADE_SEEN` sem dismiss e
> validou resgate, morte/relançamento, dismiss e abertura manual pela Ajuda.
> Pendências de uso: **TalkBack audível** e tema claro/escuro (bug de tema vira
> 5.0.2/5.1). Tag `v5.0.1` (`820f34f`); APK público assinado + SHA-256
> `208c2da036a4789ea078e42b9ab67b25966d68255ea1616aa5b57a2ed98e5378` no
> GitHub Release; `5.0.1-syncbeta`/62 é artefato separado (run
> `42526145146`, SHA-256 `fdb0952f…`). Release 61 instalado por cima do
> público 5.0/59 no A34: “Novidades” resgatado, morte antes do dismiss
> reexibiu, dismiss não repetiu e a Ajuda abriu manualmente.

> **2026-11-08 — v5.0/59 (onboarding + ajuda contextual):** primeira abertura
> guiada com quatro caminhos, skip/back, replay e “Novidades do 5.0” para
> upgrade; catálogo local de **12 tópicos** com busca/ações, ícone **Ajuda do
> BT** na top bar (Assistente, Home, Treino, Palco, Tribuna) e **Ajuda** no ⋮
> do editor; “Perguntar ao BT” não envia contexto. Gatilhos com cooldown de
> 7 dias, dismiss permanente e um por sessão; biblioteca vazia inline e
> “Entender o erro” no fluxo existente. **100% local, sem analytics/upload/PII**,
> métricas só em DataStore. **DB continua v23** (sem schema novo). Branch
> `feat/v5.0-onboarding` (`5a1e139`), CI `42478650605` verde,
> **3.040 testes JVM / 0**. A34 validou upgrade 4.1→5.0 com dados preservados,
> “Novidades”, ajuda, imagens e Tribuna 2.0; tablet validou instalação nova,
> quatro caminhos, ajuda em todas as telas, gatilho, replay e rotação.
> Pendências pós-release: **TalkBack audível** e tema claro/escuro no aparelho
> (bug de tema vira 5.0.1). Tag `v5.0.0` (`00031ae`); APK público assinado +
> SHA-256 `de98aa1a0bc89fb13810337713b4836375ad7c816a47141c14d048b9d9d3096a`
> no GitHub Release; `5.0-syncbeta`/60 é artefato separado (run
> `42517805451`, SHA-256 `4f2beac6…`). Release 59 instalado por cima do
> público 4.2/57 no A34: dados preservados, banco v23 e Tribuna 2.0/ajuda
> funcionando.

> **2026-11-07 — v4.2/57 (Tribuna 2.0 + imagens):** a Tribuna virou
> **folha de cues** (10 tipos, `timeMinutes`/`keyTerms`, editor de cues/refs,
> selo **Mapa completo/incompleto/antigo** e regeneração por seção), com
> fallback para cápsulas antigas sem migração. Imagens são **anexos privados**
> da nota/seção (`note_images`, DB **v23**, arquivos em
> `filesDir/images/<noteId>/`), com legenda/tipo/ordem, chip e miniaturas no
> editor, thumbnail 68dp no Treino e cue de imagem tocável no Palco. Geração
> por seção (600–900 tokens) usa texto, subpontos, refs e legendas; DeepSeek
> continua texto-only e as imagens não entram no HTML/RAG/provider. Palco com
> Mapa/Foco e checkpoint de tempo acumulado; demo continua como roteiro.
> Branch `feat/v4.2-tribuna-images` (`0f997ce`), CI `42428011627` verde,
> **3.024 testes JVM / 0**. A34 validou migração v22→v23 com dados
> preservados, editor de imagens, Treino/Palco com imagem e fallback antigo;
> **TalkBack audível** fica como verificação de uso pós-release. Tag
> `v4.2.0` (`9e8d78f`); APK público assinado + SHA-256
> `5885cef1cfa4fbc575aa275ef8a8836d331bd2172a8cd837f68e6f67c3464a6e` no
> GitHub Release; `4.2-syncbeta`/58 é artefato separado (run
> `42433349964`, SHA-256 `b452e4da…`). Release 57 instalado por cima do
> público 4.1/55 no A34: dados preservados, migração v22→v23 e Tribuna 2.0
> gerada no aparelho.

> **2026-11-07 — v4.0/49 (fatias 2–5 + Walkie + scroll):** aba Assistente com
> sessão de nota compartilhada e **modo Walkie** (PTT grande, última resposta
> em texto, chat completo a um toque); PTT com STT Sherpa local (fallback
> on-device), auto-TTS Piper e barge-in (áudio descartado);
> mentoria/navegação determinística (“abrir o Ponto 2”, “mostrar fontes”,
> “iniciar Treino”, “ir para o Palco”) com sugestão de próximo passo
> confirmável; contexto de Treino/Palco no Assistente (nota atual, readiness,
> última preparação escolhida, Tribuna/roteiro e retomada da sessão salva),
> sem persistir resultados de treino. Chat longo entra instantaneamente no
> fim, sem animar pelo histórico; os auto-scrolls seguintes só animam se o
> usuário estiver no fim. S-34: aviso só em `S34_DISCOURSE`. Tablet validou
> Walkie, troca de nota pelo Palco, linha de contexto, “Continuar no Palco” +
> “Iniciar Treino”, navegação e scroll; 2.983 testes JVM/0 verdes e
> lintRelease verde no CI das fatias. **Tag `v4.0.0` publicada** em
> 2026-11-07; APK assinado + SHA-256 `1618598c…` no
> [GitHub Release](https://github.com/guilhermemellojw/better-talker/releases/tag/v4.0.0);
> beta 50 rebuildado da tag como artefato separado (SHA-256 `7188b812…`).

> **2026-11-07 — pós-v4.0.0:** ícone `interpreter_mode` (Material Symbols
> Outlined) no item **Palco** da barra global; main `37eb92b` com CI verde.
> A tag/release `v4.0.0` permanece imutável; o ícone entra na próxima build.

> **2026-11-07 — fontes da mesma publicação (`9ff828c`):** quando a página
> citada está no índice mas o § citado não (numeração local do artigo, edição
> reindexada ou typo), o resolvedor devolve a **página** como PARTIAL em vez
> de “sem fonte no acervo”; o fallback global de § continua bloqueado para
> nunca trocar o § por outro artigo. O aviso agora distingue “edição no
> acervo; trecho não encontrado” de “sem fonte no acervo”, e o detalhe da
> revista mostra página/§ para separar duas refs da mesma edição.
> Reproduzido na nota “N.º 35” (Despertai! 8/2013 pág. 6 × pág. 8 §§ 1-2) e
> validado no **A34** (2 referências, grupo **Disponível**); regression check
> da nota “Acompanhe o passo…” (`rr` 37-38 §§ 18-19 × 38-39 §§ 21-24) ok.
> Testes JVM e lint verdes no CI `42405636206`.

> **2026-11-07 — v4.0.1/51 (ícone do Palco + fontes):** tag `v4.0.1` com o
> ícone `interpreter_mode` no Palco e a correção de fontes acima. APK público
> assinado + SHA-256 `74202a45…` no GitHub Release; `4.0.1-syncbeta`/52 é
> artefato separado. Atualização por cima do público 4.0/49 preserva os dados.

> **2026-11-07 — v4.0.2/53 (“Gerar Tribuna” no Palco):** o botão gera na
> própria rota do Palco, com diálogo de progresso; sucesso abre a Tribuna;
> falha mostra o motivo e ações (tentar de novo, abrir editor quando faltam
> seções, configurar IA quando falta a chave, fechar). O desvio Palco→editor
> (`generate=true`) foi removido; o “Gerar Tribuna” manual do editor continua.
> Validado no A34 com/sem chave e com seções vazias. Tag `v4.0.2`; APK público
> + SHA-256 `7a261093…` no GitHub Release; `4.0.2-syncbeta`/54 é artefato
> separado.

> **2026-11-07 — v4.1/55 (Assistente Global):** a aba Assistente funciona
> **sem nota aberta**: saudação local, PTT grande, chips de esboços recentes,
> chat global efêmero e comandos locais (listar/abrir/criar esboços, Treino,
> Palco, ajuda, configurar IA). Abrir uma nota a vincula ao Assistente e abre
> o **walkie da nota**; o editor só abre por “Abrir nota”. Texto livre usa o
> LLM quando há chave (prompt global, só títulos recentes, máx. 4 frases) e,
> sem chave, responde localmente com ação “Configurar IA”. PTT/TTS reutilizam
> o motor existente, com barge-in e parada ao sair/background; áudio nunca é
> persistido. Branch `feat/v4.1-assistant-global` (`f02c3e6`), CI `42414281250`
> verde; A34 validou lista/abrir/ambíguo/inexistente, fallback sem chave, LLM
> com chave, TTS/barge-in, rotação/background e ausência de áudio persistido;
> PTT com **voz humana pt-BR** (“Quais esboços tenho?”) listou corretamente os
> esboços. Confirmação de **TalkBack audível** fica como validação de uso
> (labels/live regions já verificados). Tag `v4.1.0`; APK público
> + SHA-256 no GitHub Release; `4.1-syncbeta`/56 é artefato separado.

> “Congelado” = não reintroduzir sem decisão explícita. “Decisão pendente” = o
> código/schema pode existir, mas o produto ainda não fechou o comportamento.

> **2026-11-06 — A34 smoke 3.4.2/47:** 47 instalado por cima do 44 com
> dados preservados. `it “Presunção” § 10` **Disponível** (ref exata),
> `w08 15/6 8-9 §§ 8-11` **Disponível + trecho aproximado** apontando para
> `w_T_20080615.jwpub`, download direto de edição ausente
> (`A Sentinela — janeiro de 2024`) pelo próprio app (`downloadEdition`) e
> estrutura do Ponto 1 com card recolhido, sem JSON, `partial=0` e
> `route=outline`. Nota e arquivo de teste removidos; a Biblioteca ficou
> com o acervo original. No tablet, 5 anexos falhados foram removidos
> (`S-38_T`, `be_T`, `th_T`, duplicata de `w_T_20080615` e `nwt_T.epub`);
> `it_T` foi mantido por ser o arquivo real do Estudo Perspicaz, e as fontes
> das notas (`it`, `w08`, TNM, `rr`, `jr`, `w17.10`, `w24.01`) seguem
> **Disponíveis**. Dívida residual explícita, sem bloquear o v4.0:
> `w08`/`rr` mais exatos e recuperação de JSON inválido/truncado do gerador.

> **2026-11-06 — beta 48:** `3.4.2-syncbeta`/48 rebuildado via
> `android-syncbeta.yml` (workflow_dispatch), artifact apenas, sem tag pública.
> SHA-256 do APK: `a9b15b33ba662329e95d96cc3c87d71f715885d09be4f9073d4fb64b7c0e3507`.
> O público 3.4.2/47 continua sendo o release atual.

> **2026-11-06 — 3.4.2/47:** estruturas (`ProposeStructure`, ângulos, texto
> colado e materialização) não espelham mais o JSON do gerador no chat: o
> turno fica `WAITING partial=0` e o card aparece no fim. Guard bloqueia JSON
> de outline no post, no cancelamento e em chat antigo. O roteador aceita
> “quero/preciso/gostaria/estruture/organize…” e “estrutura do Ponto N” com
> foco; o parser aceita `mainldea`/`publication Refs`/`biblerefs` e minutos
> decimais. Inclui o lote de estrutura de seção (refs canonicalizadas, reparo
> de minutos, `proponha`). Tablet validou os dois comandos com card recolhido
> e sem JSON; CI da branch verde. Release público 3.4.2/47; beta 43 não
> rebuildado (próximo beta usa 48).

> **2026-11-06 — 3.4.1/46:** corrige a duplicata de refs de artigo que chegavam
> pelo texto e pela seção (`it “Presunção” § 10`). A chave canônica normaliza
> `editionKey` de livro, página redundante e `paragraphEnd == paragraph`; o
> parser aceita o travessão do formato persistido. O foco usa
> `promptText`/`snippet` do checklist e refs fora do ponto ficam no inventário,
> sem falso `TEXT_NOT_FOUND`. Tablet validou 1 ref do `it`, Ponto 1
> `RESOLVED/PACK`, Introdução com `no_text=0`; `w08` segue aproximado.
> Inclui o lote de fontes da main: jr/rr, download de edições antigas e dedup
> genérica × específica. Release público 3.4.1/46; beta 43 não rebuildado
> (próximo beta usa 47).

> **2026-11-05 — 3.4.0/45:** Copilot conversa primeiro (card só em pedido
> explícito, com 1–2 frases antes), cards recolhidos com disclosure e ações
> internas, fio único com foco como lente, gate de fontes compacto e JWPUB v10.
> Tablet validou análise sem card, texto com lead-in + card recolhido,
> expandir/inserir/desfazer, “Criar proposta” expandido e ângulos recolhidos.
> A34 fez smoke test (app/nota/Copilot/gate); build debug sem chave de IA.
> Estrutura de seção ainda pode falhar no gerador (“veio incompleta”), sem
> regressão de UI. Release público 3.4.0/45; beta 43 não rebuildado (próximo
> beta usa 46).

> **2026-10-30 — produto primeiro:** Treino (`6f8f511`) e Apresentar
> (`b0dbf83`) mergeados na `main`. A versão continua **2.8.2/27** durante o
> desenvolvimento. A prep `release/v2.9.0-prep` fica **parada**; o release
> final será uma versão maior (3.0.0) depois de C2.5/M2, com bump, keystore e
> smoke em aparelho limpo. `v2.8.2` e `v2.8.1` continuam **draft**.

> **2026-10-30 — C2.5:** parser puro de Sentinela/Despertai + integração no
> detector/checklist mergeados em `59dc0aa` (2.652 testes/0, lint/CI verdes).
> `g 1/16` fica ambíguo e nunca resolve sozinho. Validado no tablet; **A34
> pendente** (aparelho não conectado). Sem bump/tag.

> **2026-10-30 — M2a:** motion do chat (handoff de fases 180 ms, raciocínio
> estável, cards top-level/aninhados, erro único, avisos, escopo com
> skeleton/crossfade, âncora de turno e pílula, reduced motion) mergeado em
> `fd0c167` (2.657 testes/0, lint/CI verdes). Validado no tablet; **A34 e
> streaming real pendentes** (tablet sem chave de IA). M2b (markdown/card shell,
> morph enviar/parar, haptics, TalkBack) é o próximo. Sem bump/tag.

> **2026-10-30 — M2b:** streaming com markdown (throttle 100 ms, terminal
> imediato), card shell durante o streaming, cursor com fade, morph
> Enviar/Parar, haptics, TalkBack e parcial cancelado com markdown/shell
> mergeado em `22eb84a` (2.668 testes/0, lint/CI verdes). **A34 validado**:
> streaming real, `Pensou por 11s`, stop com 1.876 chars, markdown sem `**`
> cru, shell sem ações e “Geração parada”. Tablet perdeu o app/dados antes da
> limpeza final; backup/DB limpa ficaram em `/tmp/opencode/`. O tablet não é
> mais gate de dados. Sem bump/tag.

> **2026-10-30 — 45 min monolítico:** BODY sem subpontos gera em 2 partes
> (23+22 min), com stitch, progresso, “Continuar geração” e retry de extensão
> no gerador (4.488 → 8.192) mergeado em `1e6ac13` (2.706 testes/0, lint/CI
> verdes). **A34 BODY rico: 5.711/5.850 palavras (97,6%) nas 2 partes**, sem
> aviso de truncamento; nota vazia mantém o fallback honesto. 45 min com
> subpontos segue na fila por subponto. Sem bump/tag.

> **2026-10-30 — status único das fontes + gate:** `CitationStatus`
> (edição/texto/ação) compartilhado por checklist, prompt e card; `citedSources`
> passa a usar a lista do `NoteSourcesResolver`; card “Fontes necessárias” com
> baixar/reindexar/confirmar/continuar sem; gate determinístico antes do LLM.
> Merge `7cff1d0` (2.734 testes/0, lint/CI verdes). **A34 validado**: w25.07
> resolvido sem card; §999 `READY/NOT_FOUND` → reindexar; lmd `INDEX_FAILED` →
> reindexar; w24.01/mwb ausentes → baixar; g 1/16 ambíguo → confirmar; “Continuar
> sem” registra a ausência sem inventar. Sem bump/tag.

> **2026-10-30 — seletor de voz + STT no aparelho:** Configurações → Voz com
> motor Piper/sistema, vozes pt-BR, “Testar voz” e switch de reconhecimento
> on-device com fallback avisado. Merge `c948f62` (2.737 testes/0, lint/CI
> verdes). **A34 validado**: lista de vozes, teste do Piper, persistência da
> voz escolhida e microfone on-device em “Ouvindo…” sem crash. Sem bump/tag.

> **2026-10-30 — v3.0.0/31 publicada:** tag `v3.0.0` no merge `497409b`;
> release workflow verde, APK assinado (CN=Better Talker) e SHA-256
> `2f7c9af3…1de783`. Reúne C2.5, M2a/M2b, 45 min, fontes/gate, voz/STT,
> Treino e Apresentar. **2.737 testes/0**, lint verde, smoke limpo arm64 no
> A34. v2.8.2/v2.8.1 seguem em draft como checkpoints.

> **2026-10-30 — v3.0.1/32 publicada:** C2.6 (tail pág./§, página sem §,
> § sem página, contexto PDF/EPUB), JWPUB confiável (schema do lmd, pageNum,
> cache UUID, mensagens específicas) e “Fontes e apoio” unificado. **2.754
> testes/0**, lint verde; A34 com JWPUBs reais (lmd/g/wp) ready e as três refs
> com texto. Tag `v3.0.1`; SHA-256 `e57bcb1d…5dd6f`.

> **2026-10-30 — v3.0.2/33 publicada:** matriz C2.6 com **49 esboços /
> 393 refs**, 0 §/parág. perdido, 0 página de revista perdida; normalização
> de NBSP/zero-width/pilcrow e caudas `g`/`wp`/`gn`/`lvs`. **2.757 testes/0**,
> lint verde. Tag `v3.0.2`; SHA-256 `9cd32c85…33481`. Texto/ação dos 49 ainda depende do acervo completo;
> 16 citações por título de livro seguem sem página.

> **2026-10-31 — v3.0.3/34 publicada:** C2.7 fecha as 16 perdas por título
> de livro (`Beneficie-se`/`Criador`) e deduplica `lvs`. Matriz final: 49
> esboços, **391 refs**, 0 página/§ perdido, 0 duplicata. **2.760 testes/0**,
> lint verde. Tag `v3.0.3`; SHA-256 `46651aa0…9d831`. Texto/ação das 391 refs continua dependente do
> acervo completo.

> **C2:** mergeado na `main` em `fb47f52` (fast-forward), **sem tag**. A
> v2.9.0/28 só depois da validação no tablet e do teste da keystore em outra
> máquina; o v2.8.2 continua **draft** e não inclui o C2.

> **M1/M1.1:** mergeados na `main` em `ffeb1e3` (fast-forward), **sem tag**.
> O turno único e o CANCELLED persistido estão validados no A34. A próxima
> frente é o card-first; a v2.9.0 continua sem tag até tablet + keystore.

> **Card-first fase 1:** mergeado na `main` em `03004b1` (fast-forward),
> **sem tag**. Alvo capturado na geração, append/replace, undo, gate “Sim”,
> stale, SUPERSEDED, verificação warn-only e “Transformar em card” validados
> no A34; **2.601 testes / 0 falhas** e `lintRelease` verde. A validação no
> tablet segue pendente; a v2.9.0 só sai depois de tablet + keystore.

> **Card-first — estados A34-only:** mergeado na `main` em `cda89e6`
> (merge commit), **sem tag**. `NO_TARGET`/`USER_PICK`, `TARGET_MISSING`,
> `FAILED` (retry da ação certa), `CANCELLED` read-only e SUPERSEDED sem
> alvo validados no A34; relatório em
> `docs/reports/validacao-2026-10-28-card-first-estados.md`.

> **Card-first fase 2:** mergeado na `main` em `1b989e3` (merge commit),
> **sem tag**. Regra única em `CHAT_TAIL_INSTRUCTIONS`, “um card por
> resposta”, segundo bloco vira texto com aviso e telemetria `EMIT`.
> Validado no A34 na rota de chat; a rota oratória legada ainda usa
> ProposalUi (fase 4). `main`: **2.608 testes / 0 falhas**, lint/CI verdes.

> **Atomicidade insert/undo:** mergeado na `main` em `29b8b93` (fast-forward),
> **sem tag**. `applyCard`/`undoCardNow` usam `db.withTransaction` (alvo +
> status do card no mesmo commit). No A34, um trigger SQLite abortando o
> UPDATE para `APPLIED` provou rollback do alvo (5.922 chars, sem texto) e
> retry sem duplicar (5.941, uma ocorrência). Relatório em
> `docs/reports/validacao-2026-10-28-card-atomicity.md`.

> **Card-first fase 3 — métricas:** mergeado na `main` em `ed51465`
> (merge commit), **sem tag**. Só telemetria `CardMetrics` (sem mudar
> UX/prompt). Corpus A34: 20 turnos, **6 cards** (6/11 turnos com texto
> aplicável), 5 SUPERSEDED, 2 aplicados, 1 undo, 1 dismissed, 1 gate
> ACCEPT; 3 pedidos ainda caíram na ProposalUi oratória e 2 ficaram na
> conversa. Relatório em `docs/reports/card-first-fase3-corpus.md`.
> `main`: **2.611 testes / 0 falhas**, lint/CI verdes.

> **Card-first fase 4:** mergeado na `main` em `0a3ea40` (fast-forward),
> **sem tag**. Oratória de texto agora emite card (`buildCardPrompt`), com
> alvo capturado, `suggestedAction=REPLACE` e confirmação; refinamento semeia
> a sessão com o conteúdo da seção; explicação/análise não vira card; refs
> têm `text`. Tablet: 6 cards / 7 pedidos com alvo (86%) e 6/8 incluindo
> transição sem S-34 (75%), 0 propostas de texto, 0 alvo errado, 1 replaced,
> 1 undo. Transição com S-34 carregado ainda precisa de amostra. `main`:
> **2.614 testes / 0 falhas**, lint/CI verdes.

> **Card-first fase 4.1:** mergeada na `main` em `afbb75f` (merge commit),
> **sem tag**. Transição com S-34 agora mira a **próxima seção** e gera card
> (validado no tablet: 771 chars, append/undo); testes de SUPERSEDED
> (3 no mesmo alvo → 2 SUPERSEDED + 1 AVAILABLE; alvos diferentes → 0;
> APPLIED preservado). Amostra: 7/8 pedidos com card (87,5%), 2 operações
> aplicadas, 0 propostas de texto, 0 alvo errado. C2 refs + M1 stop/retry
> confirmados no tablet. **2.617 testes / 0 falhas**, lint/CI verdes.

> **Tablet (gate):** fechado para C2 + M1 + card-first 1–4. No tablet:
> refs C2, stop/retry M1, fase 4 e transição com S-34; card-first 1–3 já
> estavam validados no A34. Só a **keystore em outra máquina** separa a
> `v2.9.0/28`.
>
> **Métricas:** `card_coverage = cards_emitted/text_requests` (7/8 = 87,5%);
> `card_adoption = cards_applied/cards_emitted` (2/7 = 29%, majoritariamente
> iteração/superseded, não rejeição); `card_share_of_text_outputs` é
> `cards/(cards+proposals)` = 100% e **não** é cobertura de pedidos.

> **Specs salvas:** `docs/specs/treino-mvp.md` e
> `docs/specs/apresentar-live.md` (commit `bc4f96a`). Nenhum código de
> Treino/Apresentar iniciado; dependem da `v2.9.0`.
>
> **Fase 4.2 (opcional, não bloqueia a v2.9.0):** seletor de alvo antes de
> gerar quando não há S-34 e motivos explícitos de dispensa para separar
> iteração de rejeição.

## Congelados / removidos

| Item | Status | Observação |
|---|---|---|
| Web v1 (Capacitor/Vite) | **REMOVIDO do produto** | Resíduos de build em `public/`, `tools/e2e-*.cjs`, `vite.config.ts`, `tsconfig*.json` são legado congelado; não entram no APK. Remover ou arquivar é limpeza pendente. |
| Multi-LLM (Gemini, Groq, Qwen, Gemma, fallback, circuit breaker) | **REMOVIDO** | DeepSeek-only (BYOD) + motor determinístico offline. |
| Kokoro TTS / bench de vozes | **REMOVIDO** | Piper “Jeff” 100% offline nos assets. |
| Testes de vozes obsoletos (`KokoroVoicesTest`, `KokoroBenchTest`, `PiperVoicesTest`) | **REMOVIDO** | — |
| `activity_main.xml` | **REMOVIDO** | Compose é a UI. |
| Consideração V&M (“O que você diria?”) | **REMOVIDO do fluxo de colagem** | Rejeitada com aviso; não cria nota (decisão 1b, 2026-10-25). |
| Logs `hs_err_pid*.log` / `replay_pid*.log` | **FORA DO VERSIONAMENTO** | Cobertos pelo `.gitignore`; não commitar. |

## Resolvidos na Fase 2 — 2026-10-25

Commit `506f813` · validação no A34 (S-34 novo com 30 min; Tesouros novo com 10 min) ·
**2273 testes JVM verdes**.

| Item | Decisão/ação |
|---|---|
| Título da nota V&M com tempo | Preview e nota criada sem `(N min)`; o tempo fica nos cards. |
| Minutos de INTRO/CONCLUSÃO no S-34 | O total declarado é respeitado; BODYs normalizados (30 = 1+5+11+7+5+1). |
| Contador de palavras do S-34 | Separa `developed` de `outline`: “0 desenvolvidas · N no esboço” + dica “desenvolva este ponto”. |
| Entrada do Copilot no S-34 | ✨ movido para o cabeçalho do card ativo (S-34 longo e V&M). |
| Rodapé/ruído no parser S-34 | `A SER ABRANGIDO EM N MINUTOS` vira total declarado; `N.° …-T …` é descartado. |
| Espaços internos em palavras do S-34 | `stripXml` remove tags inline sem inserir espaço; blocos viram quebra de linha. O caso “Jeov de de deá” do A34 era draft/edição, não parser. |

## Resolvidos na Fase 3 — 2026-10-25

Commit `ebdcd92` · validação no A34 (discurso 10 min/3 pontos; demonstração livre
4 min) · **2293 testes JVM verdes**.

| Item | Decisão/ação |
|---|---|
| Criar do zero | `StartFromScratchSheet` (ModalBottomSheet) com título, tipo, duração, pontos e prévia; botão desabilitado se inválido. |
| Estrutura sugerida | `StructureSuggestion`: Intro 1 + N pontos + Conclusão 1, distribuição `base + remainder`; demo livre = 1 BODY. |
| Criação atômica | `createNoteFromStructure` usa `createPastedNote`; seções nascem vazias. |
| Demonstração livre | Novo `FREE_DEMO`, 1 BODY, sem S-38; persona neutra `FREE_DEMO_SCRIPTWRITER`/`FREE_DEMO_CHAT_HINT`. |
| Parte da reunião | Fora do “Criar do zero”; continua via colagem V&M. |
| FREE_DEMO no app | Validator (single body), editor, Hub (badge “Demonstração”), Tribuna e Ensaio (turnos). |

## Resolvidos no bloco UX — 2026-10-25

Commit `5e5e1fa` · validação no A34 (S-34, V&M, FREE_DEMO) · **2293 testes JVM
verdes**.

| Item | Decisão/ação |
|---|---|
| Card minimalista | Minutos, ✨ e ⋮ no cabeçalho; badge “Demonstração” só no hub; rodapé com handle + chips de demo. |
| Linha de raciocínio | Colapsável: vazia = “+ Estrutura”; até 5 aberta; acima de 5 fechada com contagem. |
| Dica vazia | Texto “Converse com o Copilot…” removido; placeholder do editor assume. |
| Acessibilidade | Alvos de 48dp (swatches, linhas compactas, handle, menu), labels/headings, seção em português, grid de cores selecionável. |
| Validação | A34 via uiautomator (content-desc/bounds) em Home, Editor e Chat; TalkBack/Scanner manual ficam para a revisão. |

## Resolvidos no bloco Confiança — 2026-10-25

Commit `ae041b4` · validação no A34 (S-34, V&M, FREE_DEMO) · **2307 testes JVM
verdes**.

| Item | Decisão/ação |
|---|---|
| lmd apêndice | `RefDetector` reconhece `lmd apêndice A ponto N`, casa a edição e usa o ponto no conteúdo. |
| Checklist | `NoteSourcesResolver` coleta outline + seções + subpontos + plainText/mdText, deduplica e classifica. |
| UI | Chip “Fontes X/Y” no editor + `SourcesSheet`; `RESOLVED`, `EDITION_MISSING` (“Baixar/Importar”) e `TEXT_NOT_FOUND` (“Abrir edição”). |
| Sem fonte | Nota sem refs mostra “Esta nota não cita fontes…” no sheet (menu ⋮) e não exibe chip. |
| Prontidão | `readNoteReadiness` e `SetupBanner` passam a considerar refs da nota, não só outline. |
| Prompt | Inalterado; o checklist é preventivo e informativo. |
| Validação A34 | `lmd apêndice` e refs bíblicas/publicações no chip/sheet; `EDITION_MISSING` e `RESOLVED` vistos. `TEXT_NOT_FOUND` coberto por teste puro. |

## Agrupamento do sheet — 2026-10-25

Commit `93c7b02` · validação no A34 (S-34 0/69; V&M 0/2; Tesouros 5/7) ·
**2314 testes JVM verdes**.

| Item | Decisão/ação |
|---|---|
| Agrupamento primário | Publicação: TNM (`nwt`) única; `w25.07`, `lmd`, `th`… por edição. |
| TNM | Subgrupos por livro, ordem canônica; refs por capítulo/versículo. |
| Ordem | Publicações específicas faltantes primeiro, TNM, publicações específicas OK. |
| Expansão | ≤3 aberto; >3 fechado; TNM fechada; livros da TNM ≤3 abertos. |
| Resumo | X de Y + contagem `publicação resolvidas/total`. |
| Ações | Por grupo (baixar publicação; Abrir Biblioteca na TNM) e por item. |
| Acessibilidade | Cabeçalhos com contagem/status e alvos de 48dp. |

## Refs unificadas e aquisição — 2026-10-25

Commit `c53c0b7` · validação no A34 e no tablet (S-34 54/69; V&M 1/2;
TNM 53/53; download/importação de `g_T_200711.jwpub`) · **2339 testes JVM
verdes**.

| Item | Decisão/ação |
|---|---|
| Coletor único | `NoteRefsCollector` lê título, mdText/plainText, outline, seções e subpontos; dedup canônico. |
| Copilot | `citedSources`/`refScopeIds` recebem a mesma lista do checklist; Bíblia da TNM vira evidência. |
| Código de refs | `PublicationRef.article/chapter` sobrevivem ao JSON de seções/subpontos. |
| Aquisição | Ação primária “Baixar no jw.org” abre o `JwDownloadDialog` (WebView), nunca download direto por API. |
| Já baixei | “Já baixei — procurar em Downloads” abre a Biblioteca com `scanDownloads` automático. |
| Estados | `DOWNLOADING`/`INDEXING` no checklist; `attachmentDao.observe()` revalida sozinho até `No acervo`. |
| URLs antigas | Sentinela dia 1 usa `wpYYYYMMDD` (pública); dia 15 usa `wYYYYMMDD` (estudo). |
| Biblioteca | “No acervo” só com anexo indexado; falha/placeholder não conta. |
| Prompt | O caminho de geração não mudou; o coletor só amplia as refs que chegam ao prompt. |

## Correção do atalho ✨ — 2026-10-25

Commit `fe63742` · validação no tablet (S-34, V&M, FREE_DEMO) · **2339 testes
JVM verdes**.

| Item | Decisão/ação |
|---|---|
| Sintoma | O ✨ não aparecia no cabeçalho de cards no tablet; o editor rico consumia o toque que ativava a seção. |
| Correção | O atalho aparece em TODO card (não só no ativo) e o foco no editor/subponto ativa a seção. |
| Alvo | 48dp no cabeçalho; `contentDescription="Copilot deste ponto"`. |
| Validação | S-34 (2 cards), V&M (3 cards) e FREE_DEMO (1 card); clique abriu o chat com “Contexto: …”. |
| Chat E2E | S-34 → ✨ do ponto → pergunta “Mateus 7:13”; resposta usou Mateus 7:13/14 e Romanos 6:23, refs de subpontos. |

## Ensaio com análise (T3-A) — 2026-10-25

Commit `83a76e1` · validação no A34 (Stage S-34) · **2375 testes JVM
verdes**.

| Item | Decisão/ação |
|---|---|
| Entrada | STT nativo em modo sessão (`SessionSpeechRecognizer`), com reinício por segmentos. |
| Controle | Iniciar → Pausar → Retomar → Parar; pausa/erro preservam a transcrição; Result só após Parar. |
| Estados | `AnalysisState`: Idle, Listening, Paused, Processing, Interrupted, Result. |
| Erros | Silêncio reinicia; falha persistente vira Interrupted (Retomar/Parar), nunca Result automático. |
| Métricas | `SpeechMetrics`: speechDurationSec (wpm), sessionDurationSec (contexto), palavras, cobertura e muletas. |
| Feedback | `SpeechCoach`: 4 blocos + 1 exercício; sem nota/julgamento; pausas não penalizam o ritmo. |
| UI | Microfone 48dp, banner “Ouvindo/Pausado/Interrompido”, sheet com Fala/Sessão, blocos, TTS e descarte. |
| Privacidade | Áudio não é gravado; transcrição só em memória e descartada ao fechar/calcular. |
| Offline | Serviço **padrão do sistema** (mesmo caminho dos apps que funcionam); sem `EXTRA_PREFER_OFFLINE`/extras de silêncio. O reconhecimento pode usar rede; a UI avisa. |
| Calibração | Relógio de fala começa no primeiro texto reconhecido; `hasEnoughSpeech` exige ≥20 palavras, ≥10 s e ≥70 wpm; ensaio parcial não cobra cobertura/tempo total; aspas duplo-escapadas decodificadas. |
| A34 | Pausar/retomar 2× sem perder tempo/transcrição; banner Pausado congela; resultado `Fala: 18 s · Sessão: 1 min 7 s`; Interrupted com Retomar/Parar; CTA de permissão negada; `Pouca fala capturada` validado no sheet. O STT do A34 falhou com 37 palavras/45 s (49 wpm) e depois `ERROR_SERVER_DISCONNECTED`/`TOO_MANY_REQUESTS`; captura real segue pendente no tablet. |

## Geração longa com tempos corretos — 2026-10-26

Working tree sobre `275709a` · validação real no A34 · **2.445 testes JVM
verdes no tree atual** (o bloco long-text adiciona ~50; há mudanças paralelas
de contexto/refs não commitadas).

| Item | Decisão/ação |
|---|---|
| Regra de tempo | Geração de seção usa `section.minutes`, nunca o total. Demo V&M: 1 BODY, tempo da parte, `minutos × 2,5` réplicas. Discurso: Intro 1 + N BODY + Conclusão 1; BODYs usam `total - 2`. S-34: cada seção pelo seu tempo; soma = total. |
| `LongTextRequest` | `kind` SPEECH/DEMO, `scope` SECTION/TOTAL, `minutes`, `sectionMinutes`, `targetWords`/`targetTurns`. Pedido explícito diferente da seção vira `NeedsConfirmation` (o chat avisa e mantém o tempo da seção). |
| `budgetForRequest` | Discurso `palavras×1,5+200`; demo `réplicas×80+200`; coerce 2.048..8.192; acima de 8.192 divide em partes iguais. `reasoning=none`. |
| DeepSeek | `max_tokens` até 8.192; retry por `length` (até 8.192); se o endpoint rejeitar 8.192, cai uma vez para 4.096 + divisão. |
| Extensão | `section.minutes` vira extensão alvo no prompt. Card abaixo de 80% da meta com `finish=stop` dispara até 2 correções: a 1ª reescreve com piso; a 2ª reenvia o texto anterior, pede expansão dirigida e sobe o orçamento para 4.300. |
| `OutputBudget.sectionDraft` | Teto 8.192; seção de 20+ min com subpontos prefere `DraftTarget.SubPoint` (primeiro subponto); 45+ min usa 8.192. Sub-ponto usa a fatia proporcional de `section.minutes`. |
| “Texto total” | O chat não tenta um texto único: monta a fila por seção (20+ min por subponto) e abre o **DraftSheet** com “Próximo (N)”. Intro/Conclusão por seção; demo = 1 BODY. |
| Prompt/chat | `blockMinutes = section.minutes`; `CHAT_TAIL_INSTRUCTIONS` e `SPEECH_MENTOR_CHAT_HINT` respeitam o bloco e não o discurso inteiro; a “TAREFA DE TEXTO LONGO” excepciona a regra de parágrafos curtos. |
| Estrutura | `StructureSuggestion`/`PasteNoteBuilder` garantem Intro 1 + Conclusão 1 e soma = total; colagem V&M com total < 3 é ajustada para 3; demo 1 BODY. |
| Telemetria | `generation` com `route`, `budget`, `finish_reason`, `chars`, `words`, `truncated`, `hasCard`, `thinking` e `retries` no chat, oratória, draft, proposta, estrutura, minimal, outline e tribuna. |
| A34 — demo 4 min | Parte real “Cultivando o interesse (4 min) TESTEMUNHO INFORMAL… lmd lição 9 ponto 5” criada do zero: **11 réplicas**, 177 palavras no card, `budget=2048`, `finish=stop`, `truncated=false`. |
| A34 — demo 5 min | Parte real “Fazendo discípulos (5 min) ESTUDO BÍBLICO… lmd lição 12 ponto 2” do zero: **12 réplicas**, 185 palavras no card, `budget=2048`, `finish=stop`, `truncated=false`. |
| A34 — discurso 10 min | “Ter esperança é o segredo para o contentamento”: **1.159 palavras no card**, `budget=2150`, `finish=stop`, `truncated=false`, após 1 retry de extensão; ~89% da meta de 1.300 e acima do piso de 80%. |
| A34 — “texto total” | Nota “A ordem nos céus (10 min)”: o chat abriu o DraftSheet na Seção 1 e mostrou “Próximo (4)”; aceite aplicou a Intro e o Desfazer reverteu. O parcial JSON do draft não vaza mais para a bolha do chat. |
| Limite conhecido | O modelo ainda pode “fechar curto”; o piso de 80% + até 2 correções resolvem o caso de 10 min. Na medição final, 1 correção bastou. Para 20+ min, a divisão por subponto é o caminho preferido. |

## Resolvidos no C1 — orçamentos por caminho (2026-10-26)

Commit `938c0f7` · **2.518 testes JVM verdes** · CI da branch · matriz final no A34 em andamento.

| Item | Decisão/ação |
|---|---|
| Fonte única | `domain/planning/OutputBudget.kt` com funções nomeadas; `LlmRequest.maxOutputTokens = 0` significa “resolver pelo caminho”, nunca 1.000 silencioso. |
| `continueGeneration` | 1.024–8.192; usa palavras/réplicas restantes da meta persistida no payload (`targetWords`/`targetTurns`). |
| `createProposal` | 1.500–4.096; foco + nº estimado de operações; `finish=length` vira aviso, não proposta parcial. |
| `requestApplyStructure` | 4.096–8.192; acima de 30 min divide em partes com títulos explícitos e une as respostas; aviso se alguma parte cortar. |
| `answerMinimal` | 2.048–4.096; “texto completo” no modo colar é desviado para o DraftSheet (nunca resposta única). |
| `OutlineGeneratorImpl` | 1.500 fixo (TPM de 8K); `finish=length` retorna null e loga telemetria. |
| `generateOratory` | 1.280–4.096 por duração da seção/foco (substitui o 1.280 fixo); corte visível. |
| `SectionGeneratorImpl` | `OutputBudget.sectionDraft` por `section.minutes` ou pela fatia do sub-ponto; `possiblyTruncated` preservado. |
| Retry | `maxOutputTokensCap` por caminho; o provider sobe o budget no `length` até o cap e informa `retries` na telemetria (inclui extensão/fallback). |
| A34 — chat curto | `budget=2048`, `finish=stop`, 189 palavras, `retries=0`. |
| A34 — seção 5 min | `budget=2048`, `finish=stop`, 748 palavras, `retries=0`. |
| A34 — seção 15 min | `budget=3125` inicial → retry por `length` → `6250`, `finish=stop`, 2.311 palavras, `retries=2`. |
| A34 — seção 20 min | `budget=4100`, `finish=stop`, 2.327 palavras, `retries=1`. |
| A34 — seção 30 min | `budget=6050`, `finish=stop`, 3.491 palavras, `retries=1`. |
| A34 — seção 45 min | “Texto completo” em 10 partes; a seção de 45 min com 5 subpontos virou 5 alvos + intro/conclusão; nenhuma resposta única. |
| A34 — demo 4 min | `budget=2048`, `finish=stop`, 10 réplicas, 286 palavras, `retries=0`. |
| A34 — proposta/“Sim” | `route=proposal budget=1500 finish=stop`; “Sim” aplicou sem logcat de LLM; undo disponível. |
| Gaps | Seção 45 min sem subpontos ainda usa um draft no teto 8.192 (truncamento visível); V&M discurso 5 min não reexecutado do zero; `lmd_T.jwpub` continua `failed`. |
| Relatório | [validacao-2026-10-26-a34-c1.md](reports/validacao-2026-10-26-a34-c1.md). |
| Testes | `ContinueGenerationBudgetTest`, `ProposalBudgetTest`, `StructureBudgetTest`, `MinimalBudgetTest`, `OutputBudgetCapTest`, `TruncationRetryTest`, `StructurePartPromptTest`, `SectionDraftBudgetTest`. |



## Resolvidos na A — release seguro (2026-10-26)

Build 2.8.1/26 · **2.524 testes JVM verdes** · `lintRelease` verde · APK assinado
com certificado de release (RSA 4096) · SHA-256 `ca8a42a6…40e3cae`.

| Item | Decisão/ação |
|---|---|
| Backup | `allowBackup=false` + `fullBackupContent=false`; `bmgr backupnow` responde “Backup is not allowed”. Sem export/import JSON ainda, não há backup automático. |
| Chave DeepSeek | `KeystoreSecretCipher` AES/GCM, chave não exportável no Android Keystore. DataStore guarda `deepseek_api_key_encrypted`; migração automática do texto claro legado. Teste no A34: antes `sk-` presente, depois 0 ocorrências e conexão Online. |
| Keystore de release | Gerada fora do repo em `~/.better-talker-keystore/` (PKCS12, alias `better-talker`, RSA 4096, validade até 2054). `android/keystore.properties` e `*.jks` ignorados. **Backup offline ainda obrigatório.** |
| Assinatura | `signingConfigs.release` lê `keystore.properties`/env; `debuggable=false`; `apksigner verify` mostra o certificado de release. Release nunca usa a debug key. |
| Rede/WebView | `usesCleartextTraffic=false` + `network_security_config`; WebView do jw.org só HTTPS e sem `allowFileAccess`/`allowContentAccess`; PDF export offline com `blockNetworkLoads`. |
| Sync | Sem `google-services.json`, `SyncScheduler` não agenda worker; sync permanece desligado/experimental. |
| ABI | `arm64-v8a` apenas. Documentado em [RELEASE.md](RELEASE.md). |
| CI | `.github/workflows/android-release.yml`: testes, `lintRelease`, `assembleRelease`, `apksigner`, SHA-256 e publicação no GitHub Releases por tag. Dry-run [40418833924](https://github.com/guilhermemellojw/better-talker/actions/runs/40418833924) **success**; APK da CI `dd49abf1…a08781`, baixado e instalado no A34. |
| Secrets | Os quatro `BT_*` + `GOOGLE_SERVICES_JSON` configurados; `gh secret list` confirma os nomes; `github-secrets.txt` apagado. |
| A2 — A34 | Instalação limpa do release; chave salva/reiniciada/removida; S-34 `S-34_T_192.docx` importado; V&M 5 min (1+1+1+1+1) criado; demo 4 min `budget=2048/stop/hasCard`; chat real Online. Relatório: [validacao-2026-10-26-a34-release-2.8.1.md](reports/validacao-2026-10-26-a34-release-2.8.1.md). |
| A2 — smoke restante | No APK da CI: PDF A4 com texto/formatação ok (1 página, 17,8 KB), HTML share sheet ok, TTS play/stop/background ok; STT falhou no serviço do sistema (`error=10`) com fallback Interrupted/“Pouca fala” e sem áudio gravado; `w25.07` no acervo, mas chat ainda `refs=0` (C2); `lmd` “Faltando” com ação de download. |
| Release preparado | Tag `v2.8.1` → build `7eb6785`; release em **draft**; APK `1397ca64…a93133` + `.sha256`; baixado, assinatura conferida e instalado no A34. Publicar só após o teste de restauração da keystore. |
| Escopo v2.8.1 | **Opção A — security-only.** `refs=0` é limitação C2, não regressão do C1. Refs/TTS/STT P0 ficam para v2.8.2. |
| Pendente | Confirmar duas cópias offline da keystore; TTS troca de voz (sem seletor exposto); refs no chat (C2); aparelho limpo de um amigo. |

## C2a — refs com prefixo de página (2026-10-27)

Branch `feat/v2.8.2-c2a` (a partir da main `0174496`) · **2.533 testes JVM / 0 falhas** · `lintRelease` verde.

| Item | Decisão/ação |
|---|---|
| Detector | `W_CODE_RE` aceita `p.`, `p.22`, `pág.`, `página` e `pg.` antes da página; mantém `§`/`§§` e ranges. |
| Rótulo | `resolveByPageParagraph` limpa a cauda do `symbol` para não duplicar `pág. 22 §8`. |
| Testes | 8 casos puros em `RefDetectorTest` + integração `detect → resolvePublication → publicationHitOf` em `RoomReferenceResolverTest`. |
| A34 | “o que w25.07 p. 22 §8 diz sobre este ponto?” → `1 trecho(s) de publicação da pergunta injetado(s)`; resposta usa Baruque/§8. Controle “w25.07 22 §8” também injetou 1. |
| Relatório | [validacao-2026-10-27-a34-c2a.md](reports/validacao-2026-10-27-a34-c2a.md). |

## P0 voz — coexistência TTS/STT (2026-10-27)

Branch `fix/v2.8.2-voice-coexistence` (a partir da main `41c546d`) · **2.538 testes JVM / 0 falhas** · `lintRelease` verde.

| Item | Ação |
|---|---|
| Stage | `startAnalysis()` para o TTS; “Ouvir” bloqueado durante Listening/Paused/Interrupted/Processing. |
| Chat | Microfone bloqueado durante TTS; “Ouvir” bloqueado durante escuta/processamento; `startVoiceInput()` para o TTS. |
| `VoiceRouter` | `speak`/`stop`/`shutdown`/`setPremiumEnabled` param os dois motores; troca de engine não deixa fala órfã. |
| `TextToSpeechManager` | Checa retorno de `speak`; `onStop`/`onError` limpam `_speaking`. |
| `SpeechRecognizerManager` | Timeout de 5 s no `finish()`; falha de `startListening` vira erro visível. |
| Testes | `VoiceRouterBehaviorTest`, `TtsSpeakResultTest`, `AnalysisStateTest` + suíte. |
| A34 | TTS→mic parou o TTS e abriu a análise; STT do sistema continua `error=10` com fallback Interrupted. |
| Relatório | [validacao-2026-10-27-a34-voice-p0.md](reports/validacao-2026-10-27-a34-voice-p0.md). |

## Decisões pendentes (não remover ainda)

| Item | Estado atual | O que falta decidir |
|---|---|---|
| Folders/tags/cores/pin na Home | Schema Room dormente; UI usa lista simples | **Reativar, remover de vez ou manter dormente?** Não apagar schema/migração sem decisão. |
| Backup / chave DeepSeek | **RESOLVIDO na A** | `allowBackup=false` + chave no Android Keystore (2.8.1/26). |
| Keystore de release | **RESOLVIDO na A** | Keystore fora do repo; falta apenas o backup offline e os secrets do CI. |
| Sync de seções/subpontos | P0 de 2026-10-19; correções posteriores | Revisar identidade/reconciliação multi-device antes de prometer sync. |

## Regra de processo

Antes de remover qualquer item “congelado” ou mexer em decisão pendente:

1. Registrar a decisão (data + motivo) neste arquivo ou no relatório da sessão.
2. Garantir teste JVM que trave o comportamento escolhido.
3. Validar no aparelho de referência (Redmi 9 / A34 / tablet) quando a mudança for de UX.
