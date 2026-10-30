# Better Talker 🎙️

> **Editor de discursos em blocos de tempo, com Copilot de oratória, acervo local de publicações, Ensaio e métricas de fala.**
> **Estágio: v3.0.0/31 — publicado (C2.5 + M2a + M2b + 45 min + fontes/gate + voz/STT + C2 + M1 + card-first + Treino + Apresentar).**

Better Talker é um app Android nativo. O código ativo está em `android/`. Os resíduos do pacote web antigo (`public/`, `tools/e2e-*.cjs`, `vite.config.ts`, `tsconfig*.json`) são **legado congelado**: não fazem parte do build nem do produto.

O fluxo central é: importar ou escrever um esboço dividido em blocos de minutos → desenvolver cada bloco no editor rico → consultar o acervo local → pedir sugestões ao Copilot → ensaiar no Ensaio → exportar.

## 📌 Estado atual (2026-10-30)

- **Versão:** 3.0.0 (versionCode 31), publicada; **C2.5 + M2a + M2b + 45 min monolítico + status único das fontes/gate + seletor de voz/STT + C2 + M1/M1.1 + card-first 1–4.1 + Treino MVP + Apresentar live** mergeados. 45 min, gate de fontes e voz/STT validados no A34.
- **Portas de entrada (FAB `+`):** **Importar esboço S-34** (arquivo DOCX/PDF/JWPUB), **Colar parte da reunião V&M** (texto da parte S-38) e **Criar esboço do zero** (discurso público ou demonstração livre). “Nota rápida” segue como ação secundária.
- **S-34:** importação por arquivo → parser estrutural → outline persistido (seções, subpontos e referências) → o Copilot recebe a estrutura. Validado com um S-34 real de 4 seções/30 min: 75 referências (67 bíblicas + 8 de publicações). Ver [validação no Redmi 9](docs/reports/validacao-2026-10-25-redmi9.md).
- **Fase 2 (S-34):** rodapé/cobertura não viram subponto; total declarado é respeitado (30 min, não 32); contador separa **desenvolvidas × esboço**; ✨ do Copilot no cabeçalho do card ativo. Validado no A34 com import novo e nota de Tesouros de 10 min.
- **Fase 3 (Criar do zero):** sheet com título, tipo, duração, pontos e prévia; **Discurso público** (`S34_DISCOURSE`, Intro + N pontos + Conclusão) e **Demonstração livre** (`FREE_DEMO`, 1 BODY, sem S-38). Criação atômica via `createPastedNote`.
- **UX (card minimalista + acessibilidade):** minutos, ✨ e ⋮ no cabeçalho; linha de raciocínio colapsável (`+ Estrutura`, fechada acima de 5 subpontos); rodapé com handle e chips de demo; alvos de 48dp, labels, headings e swatches selecionáveis.
- **Confiança do Copilot:** chip **Fontes X/Y** no editor + sheet com Bíblia/publicações, status (`No acervo`, `Edição não importada`, `Texto não encontrado`, `Baixando`, `Indexando`) e ações; `lmd apêndice A ponto N` detectado; sem fonte fica visível antes da resposta. O sheet agrupa por publicação (TNM única), subagrupa a TNM por livro e adquire pelo **WebView do jw.org** (“Baixar no jw.org”); “Já baixei” abre a Biblioteca já procurando em Downloads.
- **Refs unificadas:** `NoteRefsCollector` é a fonte única (título, mdText/plainText, esboço, seções e subpontos); checklist e Copilot usam a mesma lista, e a Bíblia da TNM entra como evidência no prompt.
- **C2a — prefixo de página (v2.8.2, branch `feat/v2.8.2-c2a`):** o detector aceita `w25.07 p. 22 §8`, `p.22`, `pág.`, `página` e `pg.`, mantendo `§`/`§§` e ranges. A pergunta injeta 1 trecho e a resposta usa o §8; o caminho sem prefixo continua funcionando. **2.533 testes JVM / 0 falhas**; `lintRelease` verde. Ver [relatório do C2a](docs/reports/validacao-2026-10-27-a34-c2a.md).
- **P0 voz — coexistência TTS/STT (v2.8.2, branch `fix/v2.8.2-voice-coexistence`):** Stage para o TTS ao abrir o microfone e bloqueia “Ouvir” durante a análise; chat desabilita microfone durante TTS e “Ouvir” durante a escuta; `VoiceRouter` para os dois motores ao falar/trocar; `TextToSpeechManager` checa retorno de `speak` e limpa `_speaking` em `onStop`/`onError`; `SpeechRecognizerManager.finish()` tem timeout de 5 s. **2.538 testes / 0 falhas**; `lintRelease` verde. STT do sistema no A34 segue `error=10` com fallback Interrupted. Ver [relatório do P0 voz](docs/reports/validacao-2026-10-27-a34-voice-p0.md).
- **Voz/STT (3.0.0):** Configurações → Voz com motor Piper offline ou voz do sistema, lista de vozes pt-BR (offline/rede), voz padrão e teste; STT prefere o reconhecedor no aparelho (`EXTRA_PREFER_OFFLINE`/API 31+) e avisa o fallback para o serviço do sistema. **2.737 testes / 0 falhas**; A34 validou lista, Piper e microfone on-device. Ver [relatório de voz](docs/reports/validacao-2026-10-30-voz-seletor-stt.md).
- **Ensaio com análise (T3-A):** STT nativo no Stage (serviço padrão do sistema, sem forçar offline) com controle explícito **iniciar → pausar → retomar → parar**; pausa/interrupção preservam a transcrição e nunca geram resultado automático. Rate limit vira **Interrupted** com “aguarde e retome”, sem martelar o serviço. Métricas locais (tempo de fala e tempo de sessão, palavras, wpm, cobertura e muletas) e feedback em 4 blocos com um exercício. O relógio de fala começa na primeira fala; captura curta (<70 wpm) vira “Pouca fala capturada”, e ensaio parcial não cobra cobertura do texto completo. Áudio não é gravado; a transcrição é descartada ao fechar. Sem chave de IA e com fallback parcial se o STT falhar.
- **Geração longa com tempos corretos (2026-10-26):** `LongTextRequest`/`budgetForRequest` (discurso `palavras×1,5+200`; demo `réplicas×80+200`; coerce 2.048..8.192; divisão acima de 8.192). O DeepSeek usa `max_tokens` até 8.192, retry por `length`, fallback 8.192→4.096 e thinking desligado. A geração de seção usa `section.minutes` (nunca o total); se o card fica abaixo de 80% da meta, o provider faz até 2 correções de extensão (a 2ª reenvia o texto anterior e sobe o orçamento para 4.300). “Texto total” divide por seção/subponto e abre o **DraftSheet em fila** (“Próximo (N)”). S-34/colagem garantem Intro 1 + Conclusão 1 e soma = total; demo = 1 BODY. Telemetria: `generation|route|budget|finish_reason|chars|words|truncated|hasCard|thinking|retries`.
- **C1 — orçamentos por caminho (2026-10-26):** `OutputBudget` central elimina o default silencioso de 1.000 tokens. Cada caminho tem piso/teto e retry por `finish_reason=length` até o cap: `continueGeneration` 1.024–8.192 (pelo que falta da meta), `createProposal` 1.500–4.096 (foco + operações), `requestApplyStructure` 4.096–8.192 (divide acima de 30 min em partes com títulos explícitos), `answerMinimal` 2.048–4.096 (texto total vai para o DraftSheet), `OutlineGeneratorImpl` 1.500 fixo (TPM), `generateOratory` 1.280–4.096 (duração/foco) e `SectionGeneratorImpl` pela duração da seção/sub-ponto. `finish=length` nunca é sucesso silencioso: retry ou `truncated`/aviso visível.
- **A1/A2 — release seguro (2026-10-26):** chave DeepSeek cifrada com AES/GCM e chave não exportável no **Android Keystore** (migração automática do texto claro legado); `allowBackup=false`; APK de release assinado com keystore fora do repo (RSA 4096), `debuggable=false`; HTTPS only e WebView sem acesso a arquivos/conteúdo; ABI `arm64-v8a`; CI de release com `lintRelease`, `assembleRelease`, verificação por `apksigner` e SHA-256 — dry-run verde. Keystore e senhas ficam fora do repo (backup offline obrigatório). Ver [RELEASE.md](docs/RELEASE.md).
- **Validação real no A34 (2026-10-26, APK final 2.8.0/25):** matriz C1 no aparelho — chat curto `budget=2048/stop`; seção 5 min `2048/stop` (748 palavras); 15 min `3125→6250` no retry por `length`, `stop` (2.311 palavras); 20 min `4100/stop` (2.327); 30 min `6050/stop` (3.491); 45 min dividido em 10 partes (seção de 45 min com 5 subpontos → 5 alvos + intro/conclusão); demo 4 min `2048/stop` com 10 réplicas; proposta `1500/stop` e “Sim” aplicado sem LLM. Ver [relatório do C1](docs/reports/validacao-2026-10-26-a34-c1.md).
- **V&M:** colagem do texto da parte → nota com **INTRO + N BODY + CONCLUSÃO**. A parte “O que você diria?” (**consideração**) é **rejeitada com aviso** — não é suportada como discurso/demonstração.
- **Testes:** **2.737 testes JVM verdes** na main (C2.5 + M2a + M2b + 45 min + fontes/gate + voz/STT + C2 + M1 + card-first + Treino + Apresentar) · `lintRelease` verde.
- **Distribuição:** **v3.0.0/31 publicada** (APK assinado + SHA-256, arm64). O v2.8.2 e o v2.8.1 continuam em draft como checkpoints. Ver [v3.0.0](docs/releases/v3.0.0.md) e [RELEASE.md](docs/RELEASE.md).

## 🗂️ Histórico — novidades da v2.3 (2026-10-19)

- **TTS com ownership de processo:** o `VoiceRouter`/Piper agora é um singleton (`TtsLifecycle`). Sair do Ensaio, do Chat ou da Tribuna **não derruba mais** o TTS; a tela só chama `stop()`, e o engine continua vivo para a próxima tela. O contador de usuários tolera release extra (rotação/onDispose duplicado).
- **Verifier Nível 2:** as referências bíblicas do ponto atual do S-34 são resolvidas no índice TNM local e injetadas no corpus do `GroundednessVerifier` como `[ref] texto`. Citações reais deixam de ser sinalizadas; refs não resolvidas são omitidas (sem fallback fantasma).
- **Language Guide:** guia de linguagem simples injetado nas personas de chat, geração, discurso, demonstração e modo colar. Frases curtas, voz ativa e termos TJ preservados (`Jeová`, `as Escrituras`, `publicador` etc.); academicismos como `outrossim` entram na lista de termos a evitar.
- **Colar V&M — 5 formatos:** a detecção da parte de Tesouros passou a usar estrutura + posição + minutos + refs (sem depender do título), e cada frase do corpo vira um subtópico na **LINHA DE RACIOCÍNIO**. O **MINI DISCURSO** nasce vazio para geração; o texto colado permanece no `plainText` da nota para busca/prévia.
- **Testes:** 2125 testes JVM verdes na época (hoje: 2.249).

## 🆕 Novidades da v2.2

- **Reasoning visual:** durante a geração, o chat mostra o bloco **“Pensando…”** (colapsado por padrão, expansível ao toque) com o raciocínio do DeepSeek. O texto é **transitório**, não entra na resposta final nem no histórico. Funciona em **Rápido** e **Profundo**; o chip controla o esforço, não a existência do reasoning.
- **Fase B — colar unificado:** uma única folha para colar material, com prévia, classificação V&M e escolha de destino.
- **Sync v2.2:** guard de conta (`lastUid`), aborto por relógio fora de 5 min, tombstones de seção/sub-ponto/anexo/outline e `sourceUrl` para reimportar da fonte anexos “missing”.
- **Pendente:** auditoria de acessibilidade/privacidade.

---

## 📱 App Android nativo (`android/`)

Projeto Gradle/Kotlin com Jetpack Compose. Telas: Home (notas, lixeira), Editor WYSIWYG em Markdown, **Copilot em chat** com RAG 100% offline sobre as bases `be`/`th` + anexos vinculados à nota (cards de ideia inseríveis, detecção de intenção e de referências, links jw.org), Biblioteca, conta, Ensaio e TTS.

- **Banco local:** Room (`AppDatabase`), com workers em background para indexação de publicações, registro de downloads e sync.
- **Publicações:** extrator próprio de **JWPUB** com **página por § indexada** (`FirstPageNumber`/`LastPageNumber` + `DocumentParagraph`) — o RAG resolve referências como `w25.07 22 §§ 8-9` no texto literal; extratores de documentos, catálogo de publicações e auxiliar de download do jw.org.
- **IA (DeepSeek — BYOD):** a **única IA** do app. Chat com streaming token a token usando a chave pessoal do usuário (fica só no aparelho, DataStore). Sem chave ou offline, o app usa o **motor determinístico 100% offline** (não há LLM on-device).
- **TTS premium (Piper — voz "Jeff") 100% offline:** o modelo (~63MB) e o `espeak-ng-data` pt-BR (subset ~716KB) vêm **embutidos nos assets** (`assets/piper/jeff/`) e são copiados para `files/piper/jeff` no primeiro uso — sem download e sem rede. Fallback: TTS nativo do sistema.
- **Cloud/sync v2.2** com agendador próprio (`SyncWorker`, `SyncScheduler`): guard de conta (`lastUid`), clock skew de 5 min, tombstones de seção/sub-ponto/anexo/outline e `sourceUrl` para reimportar anexos da fonte.

### DeepSeek (BYOD — chat de raciocínio)

Para o uso central do app — conversar tópico por tópico, avaliar trade-offs e manter a coerência do discurso — o chat remoto pode usar o **DeepSeek**, com streaming token a token:

1. Crie uma chave em [platform.deepseek.com](https://platform.deepseek.com) (créditos pré-pagos).
2. No app: **Modelo IA → DeepSeek** → cole a chave (fica só neste aparelho, no DataStore) → **Testar conexão**.
3. Escolha o modelo: `deepseek-flash` (padrão, custo-benefício) ou `deepseek-v4-pro` (premium).

- **Custo estimado:** ~US$ 0,50/mês para uso pessoal típico (BYOD: cada usuário usa a própria chave).
- **Cache de contexto:** o prompt do DeepSeek é montado com **prefixo estável** (regras + S-34 + fontes ordenadas por id + bloco) e a parte volátil (histórico/mensagem) no fim — o cache persistente do provedor (TTL ~72h) reaproveita o prefixo entre turnos e reduz o custo. Campo volátil no prefixo destrói a economia (há teste JVM travando isso).
- **Fallback automático:** sem chave ou offline o app usa o **motor determinístico 100% offline** (o DeepSeek é a única IA do app).
- **Reasoning visual:** o provider separa `reasoning_content` do `content`; o chat mostra o bloco transitório **“Pensando…”** (colapsável) e **nunca** mistura o raciocínio à resposta persistida.
- **Uso pessoal + amigos:** a chave é pessoal; não há proxy nem chave central. Nunca comite sua chave de API.

### Modo criação (ilustrações e exemplos)

O Copilot pode criar ilustrações, metáforas, analogias e exemplos originais. Quando cria, o modelo
envolve o trecho em `〈sugestão〉…〈/sugestão〉`; o app mostra o badge **💡 Sugestão criativa** e esconde as tags.

- **O gate isenta a prosa criativa** — a criação não é tratada como fato inventado.
- **Versículo e número nunca são isentos:** se a criação citar uma referência bíblica ou um número
  sem apoio no acervo, o gate avisa/bloqueia como faria com um fato.
- Criação **sem** o marcador continua tratada como possível alucinação (gate normal).
- No chat a sugestão aparece no balão; em propostas/inserts, ANTES/DEPOIS com aceite humano.

### Colar e conversar (qualquer esboço, sem cerimônia)

Para explorar um esboço sem importar/vincular nada: **cole o texto (500+ caracteres) direto no chat**,
sem S-34 vinculado. O app detecta a colagem, guarda o texto **só na conversa** (nada persiste)
e responde com base nele, íntegro:

- **System mínimo** (3 linhas) em vez do prompt estruturado — sem regras de S-34/oratória, sem dossiê, sem RAG, sem cauda de formato.
- **Gate em modo aviso:** nada é removido; trechos sem apoio ganham `⚠️ Revise`.
- **Cauda de formato condicional:** conversa curta flui sem molde; pedidos de geração (`desenvolva`, `escreva`, `crie`, `sugira`) mantêm a formatação estruturada.
- **Limpar a conversa descarta** o esboço colado. Para persistir, use o fluxo normal (importar → vincular).

### Construir em conjunto (conversar → inserir tudo)

Para montar o discurso conversando, sem esboço inicial: **converse ponto a ponto** com o Copilot
e ao final diga *"insira tudo na nota"*:

- **Memória da construção:** a cada turno o app resume o fio (seção atual, decisões, próximo passo)
  num bloco `## MEMÓRIA DA CONSTRUÇÃO` no prompt — o copilot lembra o que foi decidido mesmo em
  conversa longa. Consensos marcados com `〈decisão〉…〈/decisão〉` viram `abordagem acordada` do tópico.
- **Inserir tudo:** o modelo emite a estrutura em Markdown canônico (`# INTRODUÇÃO`, `# SEÇÃO N`,
  `# CONCLUSÃO`); o app mostra **prévia** (títulos + primeiras linhas) e, ao confirmar, cria as
  seções na nota — **substituindo** tudo ou **adicionando** ao final, à escolha.

### Partes da reunião (V&M) — "Colar e Pronto"

Cole uma parte da reunião (ex.: `5. Cultivando o interesse (4 min) DE CASA EM CASA. Fale sobre…`)
na Home (**FAB → Colar parte da reunião V&M**). O app detecta a parte e cria uma **Demonstração**.
Formatos aceitos em [parser-formatos-colagem.md](docs/parser-formatos-colagem.md):

- **Título da nota:** parte + duração (`Cultivando o interesse (4 min)`) — vale também para
  Discurso (`A ordem nos céus (10 min)`).
- **Card:** a descrição (cenário + referência, ex. "DE CASA EM CASA… (lmd lição 7 ponto 4)")
  vira o título do card; o corpo fica livre para o diálogo.
- **Diálogo:** falas coladas (`Publicador:`/`Morador:`) ou geradas pelo Copilot ("Usar no card")
  viram turnos; os chips `+ Publicador` / `+ Morador` inserem rótulos em negrito.
- **Consideração (“O que você diria?”):** não é suportada como discurso/demonstração; a colagem
  é rejeitada com aviso e **não cria nota** (decisão de produto).

### Copilot para partes V&M (S-38)

Quando a nota é uma **Demonstração**, o prompt do Copilot ganha o bloco `## DIRETRIZES DA REUNIÃO (S-38)`:

- **Diretriz por tipo de parte** (iniciando conversas, cultivando o interesse, fazendo discípulos,
  explicando suas crenças) — tom de conversa, não discurso longo.
- **Cenário e referência** extraídos do título/corpo do card ("Cenário: De casa em casa",
  "lmd lição 7 ponto 4") — se o texto da publicação estiver nas FONTES, o Copilot usa-o.
- A resposta formatada para o card vem em `<p><b>Publicador:</b> …</p>` com o botão **"Usar no card"**.

### Ensaio com turnos (teleprompter)

No hub **Treinar**, demonstrações com diálogo abrem o teleprompter com papéis coloridos:

- Cada papel ganha uma cor; o chip **"Modo Dupla"** (rodapé; toque para mostrar os controles)
  destaca um papel e escurece as falas do outro, ciclando entre eles.
- O TTS (**Ouvir**) lê só as falas, sem os rótulos.
- O parser aceita rótulo com negrito (`<b>Publicador:</b>`) ou sem (`Publicador:`), com
  entidades do editor (`&mldr;`, `&lpar;`…) decodificadas.

### Tribuna (mapa de memória)

No hub **Apresentar**, a Tribuna mostra cápsulas por seção (IDEIA CENTRAL, TEXTO-CHAVE,
FRASE-ÂNCORA, TRANSIÇÃO). Para gerar/atualizar: no editor, **⋮ → Gerar Tribuna**
(usa a IA configurada; abre a tela e as cápsulas aparecem quando ficam prontas).

> **Contexto de uso:** projeto de uso pessoal (poucos amigos), distribuição por APK sideload — sem Play Store nesta fase.

---

## 🛠️ Tecnologias

**Nativo (`android/`):** Kotlin, Jetpack Compose, Room, Coroutines/Workers, sherpa-onnx (TTS Piper on-device), DownloadManager.

---

## 🚀 Executando o app

```bash
cd android
./gradlew assembleDebug
```
ou abra `android/` no Android Studio.

### Firebase (opcional — só metadados)
1. Crie um projeto no [Firebase Console](https://console.firebase.google.com/) com um app **Android** (`com.bettertalker.app`).
2. `google-services.json` → `android/app/google-services.json` (está no `.gitignore`).
3. O sync do Android usa **Firestore** com regras escopadas por usuário (`users/{uid}/...`), configuradas no Console. Sem configuração de Firebase, o app roda 100% offline.

---

## 📄 Licença

Distribuído sob a licença **MIT** — veja [LICENSE](LICENSE).
