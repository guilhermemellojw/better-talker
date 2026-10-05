# Better Talker 🎙️

> **Editor de discursos em blocos de tempo, com Copilot de oratória, acervo local de publicações, teleprompter e métricas de fala.**
> **Estágio: pré-release interno.**
> Dois clientes neste repositório: **Web** (React + Vite, instalável como PWA) e **Android nativo** (Kotlin + Compose — cliente ativo).

O fluxo central é: importar ou escrever um esboço dividido em blocos de minutos → desenvolver cada bloco no editor rico → consultar o acervo local → pedir sugestões ao Copilot → ensaiar no teleprompter → exportar.

---

## 🌟 O que o app faz

### 1. ✍️ Editor de discursos com blocos de tempo
- **Blocos temporais:** o discurso é organizado em abas/blocos com duração própria (ex: `(3 min)`, `(10 min)`). Cada bloco tem título, conteúdo e métricas.
- **Importar esboço:** o diálogo de importação aceita arquivos `.docx`, `.rtf`, `.pdf` e `.jwpub`, mas o parser web **lê o buffer como texto** e fatia pelos marcadores `(N min)` — funciona bem com texto puro; `.docx` binário e `.jwpub` **não** são extraídos de verdade no web (a dependência `mammoth` está no `package.json`, mas não é usada pelo código atual; extração real de JWPUB/DOC existe só no app nativo).
- **Formatação rica:** negrito/itálico/sublinhado, listas, títulos, **realce em 4 cores** (ênfase, storytelling, clímax, perguntas/metáforas) e **badges de palco inline** (pausas de 2s/3s/5s, ênfase, olhar para a plateia, sussurro, aplausos, gestos, acelerar/desacelerar ritmo). As pausas entram no cálculo de tempo.
- **Autosave local** com debounce (~400 ms) em IndexedDB, com backup em `localStorage`.

### 2. ⏱️ Métricas de fala em tempo real
- **Tempo estimado** = palavras ÷ ritmo (PPM) + duração exata das pausas de palco inseridas.
- **Seletor de ritmo:** Calmo (110 PPM), Normal (130 PPM), Enérgico (160 PPM).
- **Detector de vícios orais** em PT-BR ("tipo", "né", "basicamente", etc.), alerta de frases longas (respiração) e indicador de legibilidade.

### 3. 🤖 Copilot de oratória (Web)
- **Sem chave de API:** motor 100% offline com regras de retórica (ganchos, tricolon, analogias, alertas de cadência) e dicas contextuais geradas das métricas do bloco.
- **Com chave Gemini** (inserida nas Configurações, guardada só no IndexedDB local): ganchos de abertura, reescrita de tom (TED, pitch, motivacional, descontraído, corporativo), raio-X de palco e inserção de marcadores — via `gemini-2.5-flash`, com fallback automático para o motor offline em caso de erro.
- **Fundamentação no acervo:** o app busca trechos do acervo local relacionados ao bloco ativo e os injeta no prompt (o painel mostra quantos trechos estão fundamentando a resposta). O prompt do sistema proíbe inventar citações e manda informar quando o conteúdo não está no acervo.
- **Edição assistida:** o Copilot propõe mudanças estruturadas (reescrever, melhorar, inserir, excluir) com preview ANTES/DEPOIS — nada é aplicado sem Aceitar, e tudo pode ser desfeito/refeito (botões no topo).
- **Verificação de fidelidade:** botão "Verificar fidelidade" extrai afirmações do bloco e indica ✓ suporte, ⚠ parcial, ? insuficiente ou 💡 criativo, com evidência e proveniência — inclusive no conteúdo proposto antes do aceite.
- **Análise do discurso:** botão "Análise do discurso" identifica introdução, pontos, transições e conclusão, aponta repetições, equilíbrio e tempo estimado (~, configurável pelo ritmo) — tudo local, sem nota global, com link para cada bloco e geração de sugestões via o fluxo de propostas.
- **Arquitetura (`src/copilot/`):** `LlmProvider` com `GeminiProvider` (timeout, retry, cancelamento) e `QwenProvider` (endpoint remoto opcional via `VITE_QWEN_*`); retrieval híbrido com escopo e ranking; `ContextPack` separa fontes de conteúdo de treinamento (BE/TH).

### 4. 📚 Acervo local de publicações (BYOD)
- Importação de **`.epub` e `.pdf`** (arquivos que o próprio usuário possui) via `JSZip`/`pdfjs-dist`: até 200 seções EPUB e 500 páginas PDF por arquivo, fatiados em trechos de 3 frases com sobreposição.
- Cada trecho guarda **rastreabilidade**: símbolo detectado do nome do arquivo, seção/título, página (PDF), nº de parágrafo e referência (`símbolo + seção/página + §`).
- Arquivos **`be`/`th` são classificados como metodologia de oratória** (`speech_training`), separados do conteúdo das demais publicações — a UI mostra o selo 🎓 Treinamento e a contagem de trechos por publicação. Cada trecho de treinamento recebe uma categoria (introdução, ilustração, transição, entrega etc.), e o Copilot busca a técnica pela intenção sem jamais usar BE/TH como prova factual.
- Persistência em **Dexie/IndexedDB (v3)**. O conteúdo das publicações **nunca** sai do dispositivo (ver sync abaixo).
- **Detector de citações:** regex identifica referências no texto e gera links para o localizador do jw.org; o status permanece `missing` — **não há verificação automática** de que o conteúdo citado existe no acervo.

### 5. 🎭 Teleprompter (modo palco)
- Tela cheia com rolagem automática ajustada pelo ritmo, controle por toque/teclado, tamanho de fonte configurável, **modo espelho** para vidro de teleprompter, barra de progresso e cronômetro.

### 6. 🖨️ Exportação
- Download em **Markdown (`.md`)**, impressão (fichas de palco/cue cards) e cópia para a área de transferência. Não há exportação para `.txt`, `.docx` ou PDF direto — apenas via impressão do navegador.

### 7. 🔥 Sync em nuvem opcional (só metadados — BYOD)
- O **Android nativo** sincroniza **apenas metadados** via **Firestore** (`users/{uid}/...`, regras escopadas por usuário) — nunca texto integral nem binários de publicações.
- O sync do **web** via Realtime Database está **desativado** por segurança (as regras antigas davam acesso irrestrito a qualquer cliente autenticado anonimamente). Migrar o web para `users/$uid` é débito técnico registrado.
- Sem configuração de Firebase, os clientes rodam normalmente 100% offline.

---

## 📱 App Android nativo (`android/`)

Projeto Gradle/Kotlin com Jetpack Compose — o cliente Android ativo do produto. Telas: Home (notas, lixeira), Editor WYSIWYG em Markdown, **Copilot em chat** com RAG 100% offline sobre as bases `be`/`th` + anexos vinculados à nota (cards de ideia inseríveis, detecção de intenção e de referências, links jw.org), Biblioteca, conta e modelo de IA.

- **Banco local:** Room (`AppDatabase`), com workers em background para indexação de publicações, registro de downloads e sync.
- **Publicações:** extrator próprio de **JWPUB**, extratores de documentos, catálogo de publicações e auxiliar de download do jw.org.
- **IA on-device (Gemma 4 E2B via LiteRT-LM):** provedor local com **download pelo próprio app** (Hugging Face oficial, ~2,59 GB, só Wi-Fi, verificação de integridade SHA-256, licença Apache-2.0 com aceite e atribuições na tela "Modelo IA"). O seletor de provedor tem o modo **Automático**: DeepSeek com chave e online → Gemini/Groq com chave → Gemma local (modelo presente) → motor determinístico. Em desenvolvimento, o atalho `adb push` do `.litertlm` para `files/models` continua funcionando.
- **Cloud/sync** com agendador próprio (`SyncWorker`, `SyncScheduler`).

### DeepSeek (BYOD — chat de raciocínio)

Para o uso central do app — conversar tópico por tópico, avaliar trade-offs e manter a coerência do discurso — o chat remoto pode usar o **DeepSeek**, com streaming token a token:

1. Crie uma chave em [platform.deepseek.com](https://platform.deepseek.com) (créditos pré-pagos).
2. No app: **Modelo IA → DeepSeek** → cole a chave (fica só neste aparelho, no DataStore) → **Testar conexão**.
3. Escolha o modelo: `deepseek-flash` (padrão, custo-benefício) ou `deepseek-v4-pro` (premium).

- **Custo estimado:** ~US$ 0,50/mês para uso pessoal típico (BYOD: cada usuário usa a própria chave).
- **Cache de contexto:** o prompt do DeepSeek é montado com **prefixo estável** (regras + S-34 + fontes ordenadas por id + bloco) e a parte volátil (histórico/mensagem) no fim — o cache persistente do provedor (TTL ~72h) reaproveita o prefixo entre turnos e reduz o custo. Campo volátil no prefixo destrói a economia (há teste JVM travando isso).
- **Fallback automático:** no modo *Automático*, sem chave ou offline o app usa o **Gemma local** (se o modelo estiver baixado) ou o motor determinístico 100% offline.
- **Uso pessoal + amigos:** a chave é pessoal; não há proxy nem chave central. Nunca comite sua chave de API.

> **Contexto de uso:** projeto de uso pessoal (poucos amigos), distribuição por APK sideload — sem Play Store nesta fase.

> Estado honesto: o web e o nativo evoluíram em paralelo e **não têm paridade total** — o RAG com refs exatas e o chat são mais avançados no nativo; o teleprompter, as métricas de palco e o Copilot com Gemini existem no web.

---

## 🛠️ Tecnologias

**Web (`src/`):** React 19, TypeScript, Vite · Dexie.js (IndexedDB v3) · `jszip` + `pdfjs-dist` (acervo) · Firebase (opcional) · Lucide Icons · CSS próprio (tema escuro, sem framework) · PWA (Service Worker + manifest).

**Nativo (`android/`):** Kotlin, Jetpack Compose, Room, Coroutines/Workers, LiteRT-LM (Gemma 4 E2B on-device), DownloadManager.

> **Pré-requisito web:** Node.js **22+** (exigido pelo `pdfjs-dist`).

---

## 🚀 Executando o Web

```bash
git clone https://github.com/SEU-USUARIO/better-talker.git
cd better-talker
npm install
npm run dev        # http://localhost:5173/
npm run build      # gera dist/
npm run lint       # oxlint
```

### Como PWA (sem compilar)
1. PC e celular na mesma rede Wi-Fi; abra o endereço de rede do Vite (ex: `http://192.168.1.X:5173/`) no Chrome Android.
2. Menu ⋮ → **"Instalar aplicativo"** / "Adicionar à tela inicial".

### App nativo (Gradle)
```bash
cd android
./gradlew assembleDebug
```
ou abra `android/` no Android Studio.

### Firebase (opcional — só metadados)
1. Crie um projeto no [Firebase Console](https://console.firebase.google.com/) com um app **Android** (`com.bettertalker.app`) e um app **Web**.
2. `google-services.json` → `android/app/google-services.json` (está no `.gitignore`).
3. Copie `.env.example` para `.env.local` e preencha `VITE_FIREBASE_*` (+ `VITE_GEMINI_API_KEY`, opcional — a chave também pode ser digitada nas Configurações do app).
4. **Realtime Database:** permanece negado por segurança na versão atual; o sync do Android usa **Firestore** com regras por usuário (`firebase/firestore.rules`).

---

## 📄 Licença

Distribuído sob a licença **MIT** — veja [LICENSE](LICENSE).
