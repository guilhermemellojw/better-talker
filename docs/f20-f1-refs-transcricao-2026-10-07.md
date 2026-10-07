# Refs de estudo: transcrição de artigo/parágrafo + nwt (2026-10-07)

## Commits
- **T1 — refs bíblicas:** `5c2c459` — Build ✅ + Deploy ✅
- **T2 — artigo + parágrafo:** `549ceca` — Build ✅ + Deploy ✅
- **T3 — resolvedor:** `12824b7` — Build ✅ + Deploy ✅
- **T4p1 — seções/capítulos/EPUB:** `5a96e6f` — Build ✅ + Deploy ✅
- **T5 — nwt como base:** `c042677` — Build ✅ + Deploy ✅
- **T6 — chat/dossiê:** `bcdc081` — Build ✅ + Deploy ✅
- **T4b — parágrafo real do JWPUB:** `d74f0ad` — Build ✅ + Deploy ✅

## Testes JVM
- **1417 passed / 0 failed** (+28 na rodada).

## Diagnóstico (por que `(Jer. 41:1, 2; it "Gedalias" n.° 4)` não funcionava)
| Camada | Problema |
|---|---|
| Detecção | `Jer.` (ponto) quebrava o regex; `jer` não existia no mapa; listas (`41:1, 2`) e novo capítulo (`; 5:5`) não suportados; `it "Gedalias" n.° 4` não tinha formato |
| Modelo | `PublicationRef` só tinha symbol/page/paragraph — sem artigo/lição |
| Resolução | Casava anexo por `symbol == ref` (coluna **vazia** em todo o acervo, exceto be/th) e caía em busca textual lixo (`§4` → `"4"`, símbolo-substring) |
| Índice | Seções erradas (o verbete GEDALIAS caía em "GEADA"); `paragraph`/`page` em **0 de 291.256** passagens; símbolos errados ("s" p/ S-38, "g" sem edição) |
| Chat | Verificação carregava o anexo inteiro (LIMIT 500) e dava falso "sem fonte" |

## O que mudou
- **T1/T1b:** ponto na abreviação, aliases (`jer/1cor/2cor/heb/ro/jui/eze/...`),
  listas/faixas (`41:1, 2`; `41:1-3`) e novo capítulo (`Gên 3:19, 22, 23; 5:5`)
  sem engolir `2 Reis 25:22`.
- **T2:** `it "Gedalias" n.° 4` (aspas retas/curvas/ausentes; `n.°/n.º/número/§/parág.`)
  → `article + paragraph`; `lmd lição 3 § 4`, `rr cap. 5 § 2`, `jr 27 § 22`,
  `be pág. 52` e título integral (`Entenda a Bíblia cap. 5 parág. 10-11`);
  `chapterOf` entende `n.° N`; JSON retrocompatível.
- **T3:** resolvedor casa o anexo como a UI (`matchEdition`/`unifiedOf`) e
  transcreve a unidade — seção indexada ou passagem-título `# Título` fatiada
  até o próximo título; parágrafo exato → RESOLVED; unidade inteira → PARTIAL;
  **sem unidade → UNRESOLVED honesto**.
- **T4p1:** `splitWithSections` honra `# Título`; capítulos por extenso
  (`CAPÍTULO UM` → `Capítulo 1`); `readEpub` só lê xhtml/html de conteúdo;
  resolvedor busca por seção com variante de palavra cheia.
- **T4b:** o HTML do JWPUB marca o número como primeiro filho do parágrafo
  (`<p class="sb"><strong>4.</strong>`) — **2.339 parágrafos em 691 docs** do it
  (índices `si`/subtítulos `sn` ficam fora). O extrator emite `§N`, o índice
  guarda `paragraph`.
- **T5:** `nwt` vira base (slot + sinônimos) — `baseReady("nwt")` ✓ e a Bíblia
  entra no fluxo de "Baixar bases".
- **T6:** verificação do chat limitada à unidade citada (compartilhada com o
  resolvedor); agulhas incluem article/chapter; dossiê mostra `it “Gedalias” §4`.

## Validação no arquivo real (host, antes do device)
Replica do pipeline sobre o `it_T.jwpub` oficial (puxado do aparelho):
- Seção do verbete: **"Gedalias"** ✓ (antes caía em "GEADA");
- Parágrafos **1..5** ✓; sem vazamento para o próximo artigo (Gederotaim);
- **§4 = "“Filho de Aicão, filho de Safã.” Após a destruição de Jerusalém, em
  607 AEC, o Rei Nabucodonosor designou Gedalias como governador…"** — exatamente
  o parágrafo da citação do dono ✓;
- `markParagraphs` (função Kotlin compilada) conferida contra o HTML real:
  §1..§5 presentes.

## Pendente — validação no device
- **ADB wireless caiu** de novo durante a validação (após instalar o APK).
- Ao reconectar: (1) **Reindexar** o `it_T.jwpub` e a `nwt_T.epub` na Biblioteca
  (a nwt ganha o slot base no reindex); (2) conferir no banco (seção Gedalias +
  parágrafos); (3) importar um esboço de teste com a linha do exemplo e conferir
  os refs guardados; (4) validar no chat/dossiê.

## Estado
- Código/testes: **fechados** (7 commits, CI verde).
- Device: **pendente de reconexão** (reindex + validação visual).
