# Validação no A34 — proposta + “Sim” no cartão

**Data:** 2026-10-26
**Aparelho:** Samsung Galaxy A34 (SM-A346M), Android 16, 1080×2340
**APK:** 2.7.4 (versionCode 24) — branch `feat/copilot-proposal-action`
**Commits:** `369d240` (summary + “Sim”), `453c4da` (guarda do editor),
`5cb4816` (limpa aviso de outra seção ao aplicar)
**Testes JVM:** **2.425 / 0 falhas** no worktree

## 1. Resultado executivo

| # | Critério | Resultado |
|---|---|---|
| 1 | Gerar proposta → summary no chat | ✅ `msg-b2fbe326`: “Proposta para a seção ‘Ponto 1’: … Quer que eu aplique?” |
| 2 | “Sim” → aplica, undo disponível, sem LLM | ✅ card “Aplicado ao card.”; conteúdo do Ponto 1 mudou; logcat sem `CopilotLLM/ChatRouter` |
| 3 | “Não” → rejeita | ✅ “BT · Proposta rejeitada.”, card removido, sem LLM |
| 4 | Proposta obsoleta + “Sim” → aviso, conteúdo intacto | ✅ regra/prompt e notice do card; sem aplicação |
| 5 | Pergunta de texto + “Sim” → LLM | ✅ gate só dispara com summary pendente |
| 6 | “Sim” com texto extra → LLM | ✅ teste puro `ProposalGateTest` (`Sim, mas…` → `NONE`) |
| 7 | Outra seção + “Sim” → aviso, não aplica | ✅ card: “A proposta é da seção ‘Ponto 1’. Abra esse tópico para aplicar.”; ao voltar ao tópico, “Sim” aplicou |
| 8 | Segundo “Sim” → não reaplica | ✅ card permaneceu “Aplicado ao card.”; LLM respondeu “O Ponto 1 já está aplicado…” |
| 9 | Editor não recebe texto do chat | ✅ conteúdo do Ponto 1 não contém a pergunta nem “Sim”; só o texto aplicado |

## 2. Evidências (banco do A34)

Mensagens do chat da nota “A ordem nos ceus” (`note-c423980f…`):

```text
msg-b2fbe326  Copilot  Proposta para a seção “Ponto 1”: … Quer que eu aplique?   15:36:25
msg-62f0f4f9  Usuário  Sim                                                        15:36:51  (outro escopo; não aplicou)
msg-695d2af2  Usuário  Sim                                                        15:37:17  (aplicou)
msg-9bfedf68  Copilot  Proposta aplicada. Você pode desfazer.                     15:37:17
msg-65a12d16  Usuário  Sim                                                        15:37:55  (já aplicada)
msg-9f250f0f  Copilot  O Ponto 1 já está aplicado com os ajustes que combinamos…  15:38:00
```

Conteúdo da seção `Ponto 1`:

```text
len(contentHtml)=3497
instr(contentHtml, 'o que posso melhorar') = 0
instr(contentHtml, 'Sim') = 0
instr(contentHtml, 'A busca por uma conclusão') = 46
```

O texto aplicado entrou; a pergunta do chat e o “Sim” não vazaram para o editor.

## 3. Comportamento

- O summary é uma mensagem `text` normal; o card continua sendo a UI.
- O gate só aceita se a última fala **substantiva** do Copilot é o summary; respostas curtas:
  - sim/pode/manda/claro/isso/aceito/aplicar/confirmo/ok;
  - não/deixa/cancela/rejeito/dispensa/agora não.
- Texto extra (“Sim, mas…”) vai ao LLM; outra seção vira aviso no card; proposta
  aplicada não aceita de novo.
- Aceite usa o mesmo `acceptProposalNow` do botão (stale + undo), executado
  dentro do turno (`_chatBusy` não libera antes).
- Guarda do editor: `focusManager.clearFocus()` ao abrir o chat; sem devolver
  foco ao fechar.

## 4. Pendências/limites

- **Proposta obsoleta** foi validada pela regra/notice do card e pelo caminho
  `acceptProposalNow` (código existente); não forcei uma nota real a ficar
  stale no A34.
- **Texto extra** e **sem proposta** estão cobertos por teste puro; não repeti
  no aparelho.
- O summary usa a explicação inteira da proposta e pode ficar longo
  (ex.: 200+ chars). Polir para ~120 chars é melhoria de UX, não bloqueio.
- O A34 não tinha o `lmd` indexado; irrelevante para esta feature.
- Não há teste instrumentado Compose; a guarda do editor foi validada pelo
  conteúdo da seção no banco e pelo uso real do chat.

## 5. Reprodução

```bash
adb -s adb-RQCX209WR1Z-E8tEKj._adb-tls-connect._tcp install -r -g \
  android/app/build/outputs/apk/debug/app-debug.apk
# Abrir nota → ✨ do ponto → conversa → ⋯ da resposta → Criar proposta
# → responder "Sim" no composer
adb -s adb-RQCX209WR1Z-E8tEKj._adb-tls-connect._tcp logcat -s CopilotLLM:I ChatRouter:I
```
