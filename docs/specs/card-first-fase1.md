# Spec — Card-first, fase 1

**Status:** proposta para aprovação. Nenhum código de card-first implementado.
**Base:** `main` em `8ed47fc` (C2 + M1 + M1.1).
**Objetivo:** transformar o `<texto-do-card>` no shell principal do texto
aplicável, usando o turno único do M1 como base.

## 1. Escopo

Entra:

- card de **proposta de texto** gerado pelo Copilot;
- captura do alvo na geração;
- estados, ações, undo, verificação warn-only e gate do “Sim”;
- múltiplos cards no histórico;
- transformação de seleção em card;
- histórico com `CANCELLED`;
- telemetria e testes.

Não entra nesta fase:

- proposta de estrutura, ângulos, “insira tudo”, DraftSheet e PastePreview;
- M2 motion;
- 45 min monolítico;
- seletor de voz / STT on-device;
- sync;
- substituição cirúrgica de trecho (fica como ação avançada da fase 4).

## 2. Princípios

1. **Alvo é dado de geração, não de clique.** O card guarda o alvo capturado
   quando o texto foi gerado; o clique só usa esse alvo.
2. **Um card por resposta.** Texto aplicável dentro de
   `<texto-do-card>`; conversa fora. O modelo não escolhe alvo.
3. **O card é o item do turno.** Ele herda `turnId`, parcial, reasoning e
   CANCELLED do M1; não cria uma mensagem paralela.
4. **Nada de remoção silenciosa.** Verificação é warn-only; “Sim” não
   substitui texto sem confirmação.
5. **Stale nunca vira replace silencioso.** Append com aviso; replace
   bloqueado por padrão.

## 3. Modelo de dados

### 3.1 Alvo capturado

```kotlin
enum class CardTargetKind { SECTION, NOTE } // SUBPOINT fica para a fase 4

enum class CardCapturedFrom {
    PUSH,          // contexto empurrado pelo editor
    ACTIVE_BLOCK,  // bloco ativo/seção em foco
    SELECTION,     // seleção no editor
    WHOLE,         // discurso inteiro
    TRANSFORM,     // "Transformar em card" a partir de seleção
    USER_PICK,     // destino escolhido depois da geração (NO_TARGET)
}

enum class CardOrigin { CHAT, ORATORY, TRANSFORM, LEGACY }

data class CardTarget(
    val kind: CardTargetKind,
    val id: String,          // sectionId/subPointId/noteId
    val label: String,       // título legível
    val baseHash: String,    // hashText(contentHtml/developedHtml/richHtml na geração)
    val capturedFrom: CardCapturedFrom,
    val capturedAt: Long,
)
```

- `baseHash` usa o mesmo `hashText` do editor e do `ProposalUi`.
- O hash é calculado no **início da geração**, antes do modelo responder.
- Se o foco for um subponto, o alvo é a **seção** que o contém e o card
  mostra “Para: Ponto 2”; o subponto não é um `CardTargetKind` na fase 1.
- Se não houver alvo inequívoco, o card nasce `NO_TARGET`; nunca escolhe
  “a primeira seção” no clique.
- `NO_TARGET` só habilita insert depois de um `USER_PICK`: nesse momento o
  app captura `baseHash`, mostra “Esta sugestão foi gerada sem o contexto
  deste tópico. Revise antes de inserir.” e libera o botão.

### 3.2 Payload persistido (ChatEntity.payload, sem migration)

```text
cardSchema       1 (versão do payload)
cardTargetKind   SECTION | NOTE
cardTargetId     id do alvo
cardTargetLabel  rótulo humano
cardBaseHash     hash do alvo na geração (ou no USER_PICK)
cardCapturedFrom PUSH | ACTIVE_BLOCK | SELECTION | WHOLE | TRANSFORM | USER_PICK
cardCapturedAt   epoch ms da captura
cardTurnId       turnId que originou o card
cardOrigin       CHAT | ORATORY | TRANSFORM | LEGACY
cardSummary      resumo curto (≤160 chars) para histórico/card
cardGateMessageId id da mensagem do card (âncora do "Sim")
cardStatus       AVAILABLE | APPLIED | STALE | NO_TARGET |
                 TARGET_MISSING | SUPERSEDED | DISMISSED | FAILED
cardAppliedAt    epoch ms (quando APPLIED)
```

- `cardTurnId` é o `turnId` do M1; permite ligar card, pergunta e resposta.
- `cardOrigin` diz de qual fluxo o card nasceu; não confundir com o turno.
- `cardSummary` tem teto de 160 chars.
- `cardGateMessageId` é a mensagem que o gate consulta; não é uma segunda
  mensagem.
- O snapshot de undo fica em memória na sessão; não vai para o payload
  (evita bloat). Depois de restart, `APPLIED` continua, mas “Desfazer” fica
  desabilitado com motivo “sessão encerrada”.

### 3.3 Estado de UI

```kotlin
data class CardUi(
    val turnId: String,
    val messageId: String,
    val target: CardTarget?,
    val status: CardStatus,
    val text: String,
    val summary: String,
    val gateMessageId: String?,
    val verification: TextVerification? = null,
    val notice: String? = null,
    val canUndo: Boolean = false,
)
```

## 4. Estados e transições

| Estado | Significado | Ações |
|---|---|---|
| `AVAILABLE` | alvo existe, hash confere, card ainda não aplicado | Inserir, Substituir (com confirmação), Verificar, Dispensar |
| `APPLIED` | texto inserido; snapshot existe na sessão | Desfazer (se hash pós-apply confere), Copiar |
| `STALE` | alvo mudou desde a geração (`hash` diferente) | Inserir (append) com aviso; Substituir bloqueado; Verificar, Dispensar |
| `NO_TARGET` | geração sem alvo inequívoco | Copiar; “Escolher destino” abre o seletor; sem insert direto |
| `TARGET_MISSING` | seção/subponto/nota não existe mais | Copiar; Dispensar |
| `SUPERSEDED` | existe card mais novo para o mesmo alvo; é visual/gate, não terminal para insert | Inserir/Substituir se o hash conferir; Copiar; “Sugestão anterior”; Dispensar |
| `DISMISSED` | usuário dispensou | Copiar (histórico) |
| `FAILED` | insert/undo falhou | Tentar novamente; Copiar; Dispensar |

Transições:

```text
AVAILABLE -> APPLIED      (insert/append)
AVAILABLE -> STALE        (hash do alvo mudou)
AVAILABLE -> DISMISSED
AVAILABLE -> FAILED       (exceção no insert)
STALE     -> APPLIED      (somente append, com aviso)
STALE     -> DISMISSED
APPLIED   -> AVAILABLE    (undo restaurou o snapshot)
APPLIED   -> STALE        (texto mudou depois do apply; undo bloqueado)
SUPERSEDED                visual/gate; insert/substituir seguem permitidos
                          enquanto o hash do alvo conferir
```

`SUPERSEDED` não é estado terminal para insert: o card antigo continua
utilizável se o hash ainda for o da geração. O gate, sim, só aceita o card
mais recente que é a última fala.

`NO_TARGET` e `TARGET_MISSING` são derivados no carregamento; não são
persistidos como status principal.

## 5. Ações

### 5.1 Inserir (primário)

- **Append** ao final do alvo.
- Rótulo:
  - alvo vazio: “Usar este texto”;
  - alvo com conteúdo: “Adicionar ao final”.
- Antes: snapshot do conteúdo do alvo + `postApplyHash` previsto.
- Depois: `APPLIED`, snackbar “Texto inserido” + “Desfazer”.
- Não abre confirmação no caso normal.

### 5.2 Substituir (secundário)

- Só em `AVAILABLE` e com hash atual == `cardBaseHash`.
- Confirmação explícita: “Substituir o texto atual de «{label}»?”.
- Em `STALE`, o botão fica desabilitado; o card explica: “O texto mudou.
  Use ‘Adicionar ao final’ ou revise a sugestão.”
- Snapshot antes da substituição; undo restaura.

### 5.3 Stale

- `STALE` permite **append com aviso**: “O texto mudou desde a geração;
  vou adicionar ao final, sem substituir.”
- `STALE` **não** permite replace por padrão.
- O aviso fica dentro do card; não posta mensagem separada.

### 5.4 Verificação

- “Verificar” no menu ⋯.
- `TextVerification` é **warn-only**: mostra avisos/contagem no card;
  nunca remove texto silenciosamente.
- Inserir continua permitido com aviso; o usuário decide.
- Gate do “Sim” não é bloqueado por aviso.

### 5.5 Undo

- Snapshot em memória: conteúdo do alvo antes do insert + hash pós-apply.
- Snackbar “Texto inserido” com ação “Desfazer”.
- ⋯ → “Desfazer” enquanto o snapshot existir e o hash atual == pós-apply.
- Se o texto mudou depois do apply: undo bloqueado com motivo.
- Undo restaura o snapshot e volta o card para `AVAILABLE` (pode inserir de
  novo). O snackbar/⋯ não cria mensagem no chat.

### 5.6 Dispensar

- `DISMISSED`, card continua no histórico como “Sugestão dispensada”.
- Não há nova mensagem; gate deixa de aceitar esse card.

## 6. Gate do “Sim”

- A âncora é `cardGateMessageId` (a mensagem do card).
- O gate só aceita “Sim” se:
  - o card é `AVAILABLE` ou `STALE`;
  - não foi aplicado/dispensado;
  - a mensagem do card é a **última fala do Copilot**;
  - o card é o mais recente não terminal do alvo.
- “Sim” executa o **append**; em `STALE`, com aviso.
- “Não” dispensa o card.
- Avisos de stale/verificação ficam **dentro** do card; nunca postam uma
  mensagem separada entre a pergunta e o “Sim”.
- Se o card não for a última fala, “Sim” segue o fluxo normal do LLM.
- O `ProposalGateAction` existente pode ser reutilizado, mas a referência
  passa a ser `cardGateMessageId`; não criar um segundo gate.

## 7. Múltiplos cards

- **Um card por resposta**; o parser usa o primeiro bloco
  `<texto-do-card>` completo.
- Se vierem múltiplos blocos: o primeiro vira card; os demais viram texto
  normal com aviso “Um card por resposta” (o prompt também proíbe).
- Card novo no mesmo alvo marca os anteriores como `SUPERSEDED`.
- Cards antigos aparecem com “Sugestão anterior”; insert/substituir
  continuam habilitados se o hash conferir. Se o hash mudou, viram `STALE`.
- O gate só olha o card mais recente que é a última fala.
- Cards de alvos diferentes não se supersedem.

## 8. Transformar em card

- Ação disponível apenas com **seleção não vazia** no editor.
- Não embrulha a análise inteira: o texto selecionado é o conteúdo do card.
- No momento da transformação:
  - captura `CardTarget` com `capturedFrom = TRANSFORM`;
  - `baseHash` do alvo no momento da transformação;
  - abre revisão do card antes de inserir.
- A revisão permite editar o texto e escolher “Inserir”/“Dispensar”.
- Sem seleção, a ação fica desabilitada com explicação.
- A transformação não altera a nota até o insert.

## 9. Edição cirúrgica

- `ProposalUi` (edição de texto por proposta) continua como ação avançada
  nesta fase.
- Não criar “Substituir trecho selecionado” no card-first fase 1.
- A aposentadoria de `ProposalUi` fica para a fase 4, depois de medir o
  card-first.

## 10. Prompt

Regra única, estável:

```text
Texto aplicável (discurso, desenvolvimento, transição, introdução,
conclusão, diálogo) vai dentro de <texto-do-card>...</texto-do-card>.
Explicações, análise, perguntas e conversa ficam FORA.
Um card por resposta.
```

- O modelo **não** escolhe alvo, seção ou id.
- O alvo vem do app (`CardTarget`) capturado na geração.
- O marcador não é usado para “formatar” análise; se não houver texto
  aplicável, não há card.
- A regra entra no `CHAT_TAIL_INSTRUCTIONS` e nos prompts de geração, sem
  duplicar instruções conflitantes.

## 11. UI

- O card vive dentro do item do turno (M1), abaixo do reasoning.
- Durante `STREAMING`, o parcial é renderizado no shell do card; ao final,
  o card vira `AVAILABLE`.
- `CANCELLED`/`ERROR` são **TurnPhase do M1**, não `CardStatus`. Nesses
  estados o card é read-only: sem insert, sem replace, sem gate; mostra o
  parcial congelado + “Geração parada”/erro + Retry/Fechar. Quando o turno
  volta a `DONE`, o card vira `AVAILABLE`.
- `FAILED` existe só para falha de insert/undo, nunca para cancelamento.
- Selo de estado discreto (`Aplicado`, `Texto mudou`, `Sem destino`,
  `Sugestão anterior`).
- Ações primárias visíveis; “Verificar”, “Copiar”, “Desfazer” e “Dispensar”
  no ⋯.
- Snackbar de undo fora do card, sem criar mensagem.
- Auto-scroll continua ancorado no `turnId`/item do turno.
- Histórico: `readableChatTurn` marca `CANCELLED` como
  “Copilot (interrompido): …”; o parcial não entra como resposta final.

## 12. Telemetria

Linha única por card/turno:

```text
card|turn=...|status=...|target=SECTION:...|source=PUSH|action=INSERT|
stale=false|undo=true|gate=true|chars=...
```

- Eventos: card mostrado, insert, replace confirmado, stale append,
  undo, dismiss, verificação, gate accept/reject, transform.
- Nunca logar o texto do usuário ou o conteúdo do card; apenas metadados.

## 13. Testes obrigatórios

Puros:

- captura de `CardTarget` (kind/id/label/baseHash/capturedFrom);
- hash stale (`baseHash` ≠ atual);
- transições de estado, incluindo `SUPERSEDED`/`TARGET_MISSING`;
- ações permitidas por estado (replace bloqueado em stale);
- gate do “Sim” ancorado em `cardGateMessageId` e última fala;
- múltiplos cards: um por resposta + superseded;
- `readableChatTurn` com `cancelled=1` → “Copilot (interrompido)”;
- prompt: texto aplicável dentro do marcador, conversa fora, um card.

Integração/UI (A34 + tablet):

- card `AVAILABLE` → Inserir → `APPLIED` → Desfazer;
- Substituir com confirmação; cancelar não altera;
- stale append com aviso; stale replace bloqueado;
- fechar/reabrir mantém `APPLIED`/`STALE` e o card no histórico;
- “Sim” com card na última fala insere; “Não” dispensa; card antigo não
  aceita;
- transformar seleção em card e revisar antes de inserir;
- CANCELLED persistido + histórico marcado;
- skeleton de escopo vazio (checklist de UI da fase 1, não “depois”).

## 14. Fases

1. **Card capaz:** dados, estados, ações append/replace, undo, gate,
   persistência, UI e testes puros.
2. **Prompt card-first:** regra única, um card por resposta, telemetria.
3. **Medir:** taxa de card mostrado/aplicado/undo/stale, latência e
   regressões de chat.
4. **Aposentar proposta de texto:** `ProposalUi` vira ação avançada e depois
   sai; “Substituir trecho selecionado” só se os dados justificarem.
5. **Limpeza:** remover flags/componentes antigos, consolidar testes.

## 15. Critérios de aceite da fase 1

- Um card por resposta, com alvo capturado na geração.
- Inserir é append por padrão; substituir exige confirmação.
- Stale nunca substitui por padrão.
- Undo funciona na sessão e não cria mensagem.
- “Sim” ancora no card e não quebra com aviso/stale.
- Cards antigos ficam como “Sugestão anterior” e não aceitam gate.
- CANCELLED persistido e marcado no histórico.
- Suíte + `lintRelease` verdes; A34 e tablet validados.

## 16. Decisões em aberto (para aprovação)

- O “Transformar em card” fica no menu de seleção do editor ou no ⋯ do chat?
  Recomendação: menu de seleção, com o mesmo sheet de revisão.
- O undo persistido após restart: nesta fase fica em memória; se o produto
  exigir, persistir o snapshot em uma tabela própria depois.
- O card de alvo `NOTE` (discurso inteiro) usa append no `richHtml` da nota;
  substituir só com confirmação e hash do `richHtml`.
