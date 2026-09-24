# Better Talker 🎙️

> **Editor de discursos em blocos de tempo, com Copilot de oratória, acervo local de publicações, teleprompter e métricas de fala.**
> Dois clientes neste repositório: **Web** (React + Vite, instalável como PWA ou empacotado via Capacitor) e **Android nativo** (Kotlin + Compose).

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
- **Arquitetura (`src/copilot/`):** `LlmProvider` com `GeminiProvider` (timeout, retry, cancelamento) e `QwenProvider` (endpoint remoto opcional via `VITE_QWEN_*`); retrieval híbrido com escopo e ranking; `ContextPack` separa fontes de conteúdo de treinamento (BE/TH).

### 4. 📚 Acervo local de publicações (BYOD)
- Importação de **`.epub` e `.pdf`** (arquivos que o próprio usuário possui) via `JSZip`/`pdfjs-dist`: até 200 seções EPUB e 500 páginas PDF por arquivo, fatiados em trechos de 3 frases com sobreposição.
- Cada trecho guarda **rastreabilidade**: símbolo detectado do nome do arquivo, seção/título, página (PDF), nº de parágrafo e referência (`símbolo + seção/página + §`).
- Arquivos **`be`/`th` são classificados como metodologia de oratória** (`speech_training`), separados do conteúdo das demais publicações — a UI mostra o selo 🎓 Treinamento e a contagem de trechos por publicação.
- Persistência em **Dexie/IndexedDB (v3)**. O conteúdo das publicações **nunca** sai do dispositivo (ver sync abaixo).
- **Detector de citações:** regex identifica referências no texto e gera links para o localizador do jw.org; o status permanece `missing` — **não há verificação automática** de que o conteúdo citado existe no acervo.

### 5. 🎭 Teleprompter (modo palco)
- Tela cheia com rolagem automática ajustada pelo ritmo, controle por toque/teclado, tamanho de fonte configurável, **modo espelho** para vidro de teleprompter, barra de progresso e cronômetro.

### 6. 🖨️ Exportação
- Download em **Markdown (`.md`)**, impressão (fichas de palco/cue cards) e cópia para a área de transferência. Não há exportação para `.txt`, `.docx` ou PDF direto — apenas via impressão do navegador.

### 7. 🔥 Sync em nuvem opcional (só metadados — BYOD)
- Via Firebase (auth anônima + Realtime Database), sincroniza **apenas metadados** (títulos, blocos, durações, tags, preferências sem a chave de API) — nunca texto integral nem binários de publicações (há validação que bloqueia o sync nesses casos).
- Sem `.env.local` configurado, o app roda normalmente 100% offline; o sync é ignorado silenciosamente.

---

## 📱 App Android nativo (`android/`)

Projeto Gradle/Kotlin com Jetpack Compose, **além** do wrapper Capacitor. Telas: Home (notas, lixeira), Editor WYSIWYG em Markdown, **Copilot em chat** com RAG 100% offline sobre as bases `be`/`th` + anexos vinculados à nota (cards de ideia inseríveis, detecção de intenção e de referências, links jw.org), Biblioteca, conta e modelo de IA.

- **Banco local:** Room (`AppDatabase`), com workers em background para indexação de publicações, registro de downloads e sync.
- **Publicações:** extrator próprio de **JWPUB**, extratores de documentos, catálogo de publicações e auxiliar de download do jw.org.
- **IA on-device:** infraestrutura MediaPipe (`LlmService`) para rodar um **Qwen2.5-1.5B quantizado** (`.task`) baixado para o aparelho — porém a **URL de download ainda não está configurada** (`LlmModelConfig.DOWNLOAD_URL` vazia), então na prática o chat usa o motor determinístico local até que um modelo seja hospedado.
- **Cloud/sync** com agendador próprio (`SyncWorker`, `SyncScheduler`).

> Estado honesto: o web e o nativo evoluíram em paralelo e **não têm paridade total** — o RAG com refs exatas e o chat são mais avançados no nativo; o teleprompter, as métricas de palco e o Copilot com Gemini existem no web.

---

## 🛠️ Tecnologias

**Web (`src/`):** React 19, TypeScript, Vite · Dexie.js (IndexedDB v3) · `jszip` + `pdfjs-dist` (acervo) · Firebase (opcional) · Lucide Icons · CSS próprio (tema escuro, sem framework) · PWA (Service Worker + manifest) · Capacitor 8 (`com.bettertalker.app`).

**Nativo (`android/`):** Kotlin, Jetpack Compose, Room, Coroutines/Workers, MediaPipe LLM Inference, DownloadManager.

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

### Como APK via Capacitor
```bash
npm run build
npx cap sync        # o projeto android/ já existe no repositório
npx cap open android
```

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
4. Habilite o **Realtime Database** com escrita restrita (ex.: `"write": "auth != null"`).

---

## 📄 Licença

Ainda **sem arquivo de licença** no repositório — antes dizia MIT, mas não há `LICENSE` commitado. Definir a licença (ex.: adicionar `LICENSE` MIT) antes de distribuir.
