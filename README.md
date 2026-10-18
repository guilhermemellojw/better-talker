# Better Talker 🎙️

> **Editor de discursos em blocos de tempo, com Copilot de oratória, acervo local de publicações, Ensaio e métricas de fala.**
> **Estágio: v2.0 — release interno (sideload).**

Better Talker é um app Android nativo. Este repositório contém apenas o projeto Android em `android/`. O pacote web legado foi removido.

O fluxo central é: importar ou escrever um esboço dividido em blocos de minutos → desenvolver cada bloco no editor rico → consultar o acervo local → pedir sugestões ao Copilot → ensaiar no Ensaio → exportar.

---

## 📱 App Android nativo (`android/`)

Projeto Gradle/Kotlin com Jetpack Compose. Telas: Home (notas, lixeira), Editor WYSIWYG em Markdown, **Copilot em chat** com RAG 100% offline sobre as bases `be`/`th` + anexos vinculados à nota (cards de ideia inseríveis, detecção de intenção e de referências, links jw.org), Biblioteca, conta, Ensaio e TTS.

- **Banco local:** Room (`AppDatabase`), com workers em background para indexação de publicações, registro de downloads e sync.
- **Publicações:** extrator próprio de **JWPUB** com **página por § indexada** (`FirstPageNumber`/`LastPageNumber` + `DocumentParagraph`) — o RAG resolve referências como `w25.07 22 §§ 8-9` no texto literal; extratores de documentos, catálogo de publicações e auxiliar de download do jw.org.
- **IA (DeepSeek — BYOD):** a **única IA** do app. Chat com streaming token a token usando a chave pessoal do usuário (fica só no aparelho, DataStore). Sem chave ou offline, o app usa o **motor determinístico 100% offline** (não há LLM on-device).
- **TTS premium (Piper — voz "Jeff") 100% offline:** o modelo (~63MB) e o `espeak-ng-data` pt-BR (subset ~716KB) vêm **embutidos nos assets** (`assets/piper/jeff/`) e são copiados para `files/piper/jeff` no primeiro uso — sem download e sem rede. Fallback: TTS nativo do sistema.
- **Cloud/sync** com agendador próprio (`SyncWorker`, `SyncScheduler`).

### DeepSeek (BYOD — chat de raciocínio)

Para o uso central do app — conversar tópico por tópico, avaliar trade-offs e manter a coerência do discurso — o chat remoto pode usar o **DeepSeek**, com streaming token a token:

1. Crie uma chave em [platform.deepseek.com](https://platform.deepseek.com) (créditos pré-pagos).
2. No app: **Modelo IA → DeepSeek** → cole a chave (fica só neste aparelho, no DataStore) → **Testar conexão**.
3. Escolha o modelo: `deepseek-flash` (padrão, custo-benefício) ou `deepseek-v4-pro` (premium).

- **Custo estimado:** ~US$ 0,50/mês para uso pessoal típico (BYOD: cada usuário usa a própria chave).
- **Cache de contexto:** o prompt do DeepSeek é montado com **prefixo estável** (regras + S-34 + fontes ordenadas por id + bloco) e a parte volátil (histórico/mensagem) no fim — o cache persistente do provedor (TTL ~72h) reaproveita o prefixo entre turnos e reduz o custo. Campo volátil no prefixo destrói a economia (há teste JVM travando isso).
- **Fallback automático:** sem chave ou offline o app usa o **motor determinístico 100% offline** (o DeepSeek é a única IA do app).
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
na Home (**FAB → Inserir material → Colar texto**). O app detecta a parte e cria uma **Demonstração**:

- **Título da nota:** parte + duração (`Cultivando o interesse (4 min)`) — vale também para
  Discurso (`A ordem nos céus (10 min)`).
- **Card:** a descrição (cenário + referência, ex. "DE CASA EM CASA… (lmd lição 7 ponto 4)")
  vira o título do card; o corpo fica livre para o diálogo.
- **Diálogo:** falas coladas (`Publicador:`/`Morador:`) ou geradas pelo Copilot ("Usar no card")
  viram turnos; os chips `+ Publicador` / `+ Morador` inserem rótulos em negrito.

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
