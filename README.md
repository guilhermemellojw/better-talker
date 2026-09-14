# Better Talker 🎙️

> **O Editor de Texto e Copilot de IA para Oradores, Palestrantes e Criadores de Discursos.**  
> Suporte completo para **Web e Android** com arquitetura **100% Offline-First** e **Estilização de Texto em Tempo Real**.

---

## 🌟 Principais Recursos

### 1. ✍️ Editor de Oratória com Blocos de Tempo
- **Blocos Temporais:** O editor organiza o discurso em abas por blocos de tempo (ex: `2 min`, `8 min`, `12 min`). Cada bloco tem métricas independentes.
- **Importar Esboço:** Upload de `.docx`, `.rtf`, `.pdf` ou `.jwpub` — parser extrai automaticamente a estrutura por blocos.
- **Marca-Texto de Entonação Vocal:**
  - 🟡 **Amarelo:** Ênfase e Atenção Normal.
  - 🟢 **Verde:** Storytelling, Conexão e Cadência Calma.
  - 🔴 **Vermelho:** Clímax, Ponto Alto e Impacto Dramático.
  - 🟣 **Roxo:** Perguntas Retóricas e Metáforas.
- **Badges de Palco Inline:** Marcadores clicáveis inseridos diretamente no fluxo do texto.

### 2. ⏱️ Telemetria & Métricas de Fala ao Vivo
- **Tempo Estimado de Discurso:** Calculado em tempo real com base no ritmo em palavras por minuto (PPM/WPM) somado ao tempo exato de todas as pausas de palco.
- **Seletor de Ritmo:**
  - Calmo (110 PPM - solene / reflexivo)
  - Normal (130 PPM - padrão palestras TED)
  - Enérgico (160 PPM - pitch de vendas dinâmico)
- **Detector de Vícios Orais:** Identifica palavras de preenchimento ("tipo", "né", "literalmente", "basicamente", etc.).
- **Alerta de Respiração:** Detecta períodos longos demais para evitar falta de ar no palco.

### 3. 🤖 Copilot de Oratória (Híbrido Offline / Gemini AI)
- **Modo 100% Offline Embutido:** Regras de retórica clássica, ganchos provocativos, tricolon e diagnóstico de oratória sem depender de internet.
- **Ajustador de Tom:** TED/Inspirador, Pitch de Vendas, Motivacional, Humorístico e Corporativo.
- **Ações Rápidas de IA:**
  - 🎣 Criar Ganchos de Abertura (primeiros 30 segundos)
  - 🎭 Reescrever Trecho com foco em Cadência Oral
  - 🔍 Raio-X de Palco (Crítica construtiva)
  - ⚡ Inserção Automática de Marcadores de Palco
- **Google Gemini API:** Suporte para inclusão da chave de API no menu de configurações com armazenamento local seguro.

### 4. 🎭 Modo Palco (Teleprompter em Tela Cheia)
- Modo de ensaio para Web e smartphones Android.
- Rolagem automática suave ajustada pelo ritmo (WPM).
- Controle por toque ou barra de espaço.
- Ajuste dinâmico de tamanho de fonte para leitura à distância.
- **Modo Espelho (Mirror Flip)** para púlpitos com hardware físico de vidro de teleprompter.
- Barra de progresso e cronômetro de palco.

### 5. 🖨️ Fichas de Palco (Cue Cards)
- Geração e impressão de fichas pautadas de bolso divididas por seções para levar ao púlpito.
- Exportação em arquivos Markdown (.md) e Texto Puro (.txt).

---

## 🛠️ Tecnologias Utilizadas

- **Frontend:** [React 19](https://react.dev/), [TypeScript](https://www.typescriptlang.org/), [Vite](https://vite.dev/)
- **Ícones:** [Lucide React](https://lucide.dev/)
- **Estilização:** Vanilla CSS Design System (Dark theme de alta performance, sem dependências de frameworks pesados)
- **Persistência:** [Dexie.js](https://dexie.org/) (IndexedDB v2) com fallback resiliente (100% Offline-First)
- **Parser:** [mammoth](https://mammoth.js.org/) (.docx), [pdfjs-dist](https://mozilla.github.io/pdf.js/) (.pdf), [JSZip](https://stuk.github.io/jszip/) (.epub)
- **Multiplataforma:**
  - **PWA:** Service Worker e Web Manifest para instalação instantânea no Android e desktop
  - **Capacitor:** Configuração pronta para empacotamento nativo Android (`@capacitor/android`)

> **Pré-requisito:** Node.js versão **22** ou superior (necessário para `pdfjs-dist`).

---

## 🚀 Como Executar Localmente

### Pré-requisitos
- [Node.js](https://nodejs.org/) (versão 22 ou superior)
- Git

### Instalação

1. Clone o repositório:
```bash
git clone https://github.com/SEU-USUARIO/better-talker.git
cd better-talker
```

2. Instale as dependências:
```bash
npm install
```

3. Inicie o servidor de desenvolvimento:
```bash
npm run dev
```

4. Abra no navegador:
```
http://localhost:5173/
```

---

## 📱 Executando no Android

### Opção 1: Como PWA (Rápido e sem compilar)
1. Conecte o smartphone Android na mesma rede Wi-Fi do computador.
2. Acesse o endereço IP da rede local fornecido pelo Vite (ex: `http://192.168.1.X:5173/`).
3. No menu do navegador (três pontinhos), toque em **"Instalar aplicativo"** ou **"Adicionar à tela inicial"**.
4. O Better Talker passará a rodar em tela cheia como um aplicativo nativo, salvando tudo localmente.

### Opção 2: Gerando APK Nativo com Capacitor & Android Studio
1. Compile o projeto:
```bash
npm run build
```

2. Inicialize o projeto Android nativo:
```bash
npx cap add android
```

3. Sincronize os arquivos:
```bash
npx cap sync
```

4. Abra no Android Studio para rodar no emulador ou gerar o APK:
```bash
npx cap open android
```

### 🔥 Vinculando ao Firebase (Cloud Sync de Metadados — BYOD)

O Better Talker sincroniza **apenas metadados** (título, blocos, duração, categoria) para a nuvem — **nunca** o conteúdo de publicações (`publications/`). Isso respeita o modelo **BYOD**: a biblioteca de publicações é sempre local.

**Passo a passo:**

1. No [Firebase Console](https://console.firebase.google.com/), crie um projeto e adicione um app **Android** com o package `com.bettertalker.app`.
2. Baixe o arquivo `google-services.json` e coloque em `android/app/google-services.json` (o template Capacitor já aplica o plugin `google-services` automaticamente quando o arquivo existe).
3. Adicione um app **Web** ao mesmo projeto e copie as configurações para `.env.local` (veja `.env.example`):
   - `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`, `VITE_FIREBASE_STORAGE_BUCKET`, `VITE_FIREBASE_MESSAGING_SENDER_ID`, `VITE_FIREBASE_APP_ID`.
4. Habilite o **Cloud Firestore** em *Build > Firestore Database*.
5. Nas **regras de segurança do Firestore**, restringa gravações (ex.: `allow write: if request.auth != null;`).

> O `google-services.json` está no `.gitignore` — nunca commite credenciais.
> Sem o `.env.local`, o app roda normalmente 100% offline; o sync é ignorado silenciosamente.

---

## 📄 Licença

Distribuído sob a licença MIT. Sinta-se livre para usar, aprimorar e compartilhar!
