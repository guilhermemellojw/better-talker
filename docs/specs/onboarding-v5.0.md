# Spec — Onboarding e ajuda contextual (v5.0)

**Versão:** 5.0/59 · **Beta:** 5.0-syncbeta/60
**Branch:** `feat/v5.0-onboarding`
**Base:** main `8784829` (v4.2.0)
**Status:** aprovado para implementação

## 1. Persona e promessa

O BT é um mentor de oratória que conduz o primeiro uso e acompanha o usuário
durante o app. Fala pouco, em português, e oferece uma ação concreta por vez.

Promessa: instalar → entender o que fazer → criar/importar a primeira nota →
treinar/apresentar, sem tutorial externo e sem beco sem saída.

Princípios:

- **Local-first:** catálogo de ajuda, onboarding e comandos funcionam sem
  chave e sem rede.
- **Sem upload, analytics ou PII.** Nada de conteúdo de nota, fontes, chat,
  áudio ou títulos é enviado automaticamente.
- Ajuda curta e acionável; uma ação por vez.
- Proativo com contenção: sem nagging, sem always-on/wake word.
- O usuário pode pular, dispensar e reabrir.
- Tudo em pt-BR, usando os componentes/paleta existentes.

O catálogo do 5.0 é 100% local. O chat global da 4.1 é opcional e segue a
política existente; “Perguntar ao BT” **não** envia o contexto de tela.

## 2. Primeira abertura

### 2.1 Estados

`onboarding_v5_state` em DataStore:

- `NOT_STARTED`: instalação nova ou upgrade ainda não apresentado.
- `DONE`: onboarding concluído.
- `DISMISSED`: pulou; não repete automaticamente.
- `UPGRADE_SEEN`: usuário com notas já viu o “Novidades do 5.0”.

Regra de decisão depois do banco pronto:

- sem notas + `NOT_STARTED` → onboarding completo;
- com notas + `NOT_STARTED` → “Novidades do 5.0” e marca `UPGRADE_SEEN`;
- `DONE`/`DISMISSED`/`UPGRADE_SEEN` → não mostrar automaticamente.

Trocar tema, rotacionar ou voltar ao app não reabre onboarding nem “Novidades”.

### 2.2 Fluxo

Instalação nova: rota dedicada `onboarding`, após o banco estar pronto. Não
bloqueia o app; “Agora não” sempre disponível. Back/gesture = “Agora não”
(marca `DISMISSED`, abre o Assistente, sem loop).

1. **Boas-vindas:** “Oi! Eu sou o BT. Vou te ajudar a preparar, treinar e
   apresentar sua parte. O que você quer fazer primeiro?”
2. **Quatro caminhos:** Importar S-34 · Colar V&M · Criar do zero · Só
   explorar.
3. **Importar S-34:** entrega o controle ao fluxo existente; se cancelar,
   volta aos quatro caminhos sem erro. Ao concluir, o resumo/dica acontece no
   destino, sem duplicar navegação.
4. **Colar V&M:** abre o sheet existente; ao concluir, dica no destino.
5. **Criar do zero:** reutiliza o fluxo existente; ao concluir, abre o editor
   com dica curta. Não criar um segundo sheet de criação na 5.0.
6. **Só explorar:** tour local de 3 cartões (Assistente, Esboços,
   Treino/Palco), sem dados de exemplo; termina em “Abrir Esboços” ou “Falar
   com o BT”.
7. **Conclusão:** marca `DONE`; não repete.

Texto-first. “Ouvir” é opcional (TTS local), sem auto-falar. Sem chave/rede,
os quatro caminhos funcionam; a chave só é explicada quando a geração for
necessária.

### 2.3 Upgrade

`ModalBottomSheet` curto “Novidades do 5.0” com 3 bullets, “Ver ajuda” e
“Agora não”. Marca `UPGRADE_SEEN` ao exibir; não repete automaticamente.

### 2.4 Rever primeiros passos

Replay visual do onboarding, sem resetar `onboarding_v5_state` e sem marcar
`NOT_STARTED`. Funciona offline e a partir da Ajuda. Ao fechar, volta ao
ponto anterior.

## 3. Catálogo de ajuda local

12 tópicos com id, pergunta, resposta curta (2–3 frases), passos (2–4), ação e
keywords:

| id | pergunta | ação |
|---|---|---|
| `primeira-nota` | Como começo minha primeira nota? | Abrir os quatro caminhos |
| `importar-s34` | Como importo um esboço S-34? | Abrir importação |
| `colar-vm` | Como colar uma parte da reunião V&M? | Abrir sheet de colar |
| `criar-do-zero` | Como criar um esboço do zero? | Abrir criação |
| `copilot` | O que o Copilot faz? | Abrir Assistente/chat |
| `chave-ia` | Preciso de chave de IA? | Abrir Modelo IA |
| `fontes` | Como funcionam as fontes e a Biblioteca? | Abrir Biblioteca/Fontes |
| `tribuna` | O que é a Tribuna 2.0? | Abrir Tribuna/Palco |
| `imagens` | Como adiciono imagens à nota? | Abrir sheet de imagens |
| `treino` | Como treino meu discurso? | Abrir hub Treino |
| `palco` | Como apresento no Palco? | Abrir hub Palco |
| `voz-offline-privacidade` | Como funciona a voz? Meus dados sobem? | Abrir Ajustes de Voz |

Busca local por título/keywords. Ações válidas de qualquer tela; se exigir
nota, usar a nota atual ou abrir o picker.

## 4. Entrada de ajuda

- **Ícone na top bar** das telas principais (Assistente, Esboços/Home, hubs
  Treino/Palco, Tribuna). Não usar botão flutuante.
- No editor, item **“Ajuda”** no menu ⋮.
- Oculto no Palco/Treino imersivos, com teclado aberto e quando houver
  sheet/modal por cima.
- Abre o sheet **“Ajuda do BT”**: busca, tópicos filtrados pela rota, ações,
  “Perguntar ao BT” e “Rever primeiros passos”.
- “Perguntar ao BT” abre o chat global; não transporta contexto de ajuda/tela.

## 5. Contexto por tela

Contrato local, sem conteúdo sensível:

```kotlin
data class AssistantHelpContext(
    val route: String,
    val noteId: String? = null,
    val noteTitle: String? = null,      // exibição local apenas
    val sectionId: String? = null,
    val sectionTitle: String? = null,   // exibição local apenas
    val practice: PracticeSnapshot? = null,
    val errorKind: HelpErrorKind? = null,
    val source: HelpSource,
)

enum class HelpSource { FIRST_RUN, HELP_BUTTON, TRIGGER, ERROR_ACTION, HELP_REOPEN }
enum class HelpErrorKind { NO_KEY, MISSING_SOURCES, GENERATION, ANALYSIS, OTHER }
enum class HelpAction { OPEN_IMPORT, OPEN_PASTE, OPEN_CREATE, OPEN_EDITOR,
    OPEN_LIBRARY, OPEN_MODEL, OPEN_TRAIN, OPEN_PRESENT, OPEN_TRIBUNA,
    OPEN_VOICE, OPEN_ASSISTANT, OPEN_HELP_TOPIC, NONE }
```

`PracticeSnapshot` tem campos explícitos (modo, escopo/seção atual,
prontidão), sem mapa genérico. Não incluir HTML/texto da nota, fontes, chat,
áudio ou PII. Títulos são só exibição; nunca prefill automático do LLM.

O BT pode explicar, navegar, abrir sheets, iniciar preparação e abrir Modelo
IA. Não pode editar, aceitar propostas, gerar texto, excluir ou mudar ajustes
sem toque. Toda ação exige confirmação/toque.

## 6. Gatilhos proativos mínimos

Ativos só depois de `DONE`, `DISMISSED` ou `UPGRADE_SEEN`; nunca com o
onboarding aberto.

| gatilho | quando | ação | regra |
|---|---|---|---|
| `empty_library` | Esboços sem notas | Importar/Criar | dismiss permanente; ignorar = cooldown 7 dias |
| `no_key` | Geração sem chave | Configurar IA | só se o erro ainda não tem CTA; cooldown 7 dias |
| `missing_sources` | Geração bloqueada por fontes | Biblioteca/Fontes | idem |
| `error_help` | Erro de geração/análise | Abrir tópico | link dentro do erro, não popup |
| `stage_tribuna` | Palco sem Tribuna | — | já tratado na 4.0.2; não duplicar |

Regras:

- “Não mostrar de novo” = permanente, sobrevive a reinício/upgrade.
- Ignorar/fechar sem dismiss = cooldown 7 dias.
- Nunca dois avisos ao mesmo tempo.
- Prioridade: `no_key` > `missing_sources` > `empty_library` > `error_help`.
- Sem gatilhos no Palco/Treino, durante geração, digitação, chat ativo ou
  sheet aberto. No máximo um por sessão.
- `TriggerState` em DataStore: `seen`, `dismissed`, `lastShownAt`.

## 7. Critérios de aceite

- [ ] Instalação nova, sem chave, percorre os quatro caminhos sem beco.
- [ ] Onboarding só aparece depois do banco pronto.
- [ ] Back no onboarding = “Agora não”, sem loop.
- [ ] Ajuda abre em ≤2 toques; todo tópico tem ação válida; busca offline.
- [ ] Ação funciona de qualquer tela; usa a nota atual ou abre o picker.
- [ ] “Não mostrar de novo” é permanente; ignorar tem cooldown de 7 dias.
- [ ] Nenhum gatilho durante geração/digitação/chat/sheet/Palco/Treino.
- [ ] Trocar tema/rotação não reabre onboarding nem “Novidades”.
- [ ] TalkBack: foco inicial no título do sheet, labels do botão de ajuda,
      ações dos cartões e live regions.
- [ ] Ícone de ajuda na top bar; editor no ⋮; oculto nos imersivos.
- [ ] “Rever primeiros passos” não reseta estado e funciona offline.
- [ ] “Perguntar ao BT” não envia contexto automaticamente.
- [ ] Nenhuma regressão em Tribuna 2.0/imagens/4.2.
- [ ] Tudo pt-BR; sem upload/analytics/PII.
- [ ] Ajuda local <100ms; onboarding não atrasa a abertura.
- [ ] A34 obrigatório; tablet quando disponível; rotação/tema/reduced motion.

## 8. Métricas locais

Contadores em DataStore, sem PII/upload: `onboarding_started`,
`path_import`, `path_paste`, `path_create`, `path_explore`,
`onboarding_completed`, `onboarding_skipped`, `upgrade_welcome_seen`,
`help_opened`, `topic_opened`, `help_action_clicked`, `trigger_shown`,
`trigger_dismissed`, `help_reopened`. Timestamps só para cooldown. Nada é
enviado; em debug os contadores podem ser inspecionados.

## 9. Fora de escopo (v5.0)

Proatividade ampla/recuperação guiada (5.1); histórico global persistente;
LLM streaming; novo sheet de “Criar do zero”; extração de imagens de
DOCX/PDF; always-on/wake word; analytics/cloud; mudanças na 4.2.

## 10. Versão, branch e validação

- v5.0/59 público; 5.0-syncbeta/60.
- Branch `feat/v5.0-onboarding`.
- Sem 4.2.1; o flake foi test-only.
- Docs: este arquivo; release notes em `docs/releases/v5.0.0.md` no release.
- Validação: testes JVM/lint/CI; A34 para onboarding, ajuda, gatilhos,
  TalkBack e regressão 4.2. Tablet quando disponível.
- Tag/release só com pedido explícito.
