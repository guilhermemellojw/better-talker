# Sessão 2026-10-23 — Sessão 2 (C6/C8 + harness)

**Build efetiva:** v2.7.2 (versionCode 22) — worktree `fix/v2.7.2-tts-anr`, com mudanças não commitadas de TTS/ANR. O comportamento de chat é o do v2.7.1 + os fixes desta sessão; o patch 2.7.2 não altera prompt/router.
**Device:** Galaxy A34 (SM-A346M), Android 16.
**Escopo:** Introdução, nota “Ter esperança é o segredo para o contentamento (10 min)”.
**Harness:** ADB Keyboard (`ADB_INPUT_B64`) + “Limpar conversa” no topo.

> ⚠️ **Worktree compartilhado:** a branch ativa é `fix/v2.7.2-tts-anr` e há mudanças não commitadas do usuário em TTS, Retrieval, PubCatalog, TextNorm e build.gradle. **Nada foi commitado nesta sessão** para não misturar esses trabalhos. As mudanças desta sessão estão isoladas em `ChatScreen.kt`, `ChatRouter.kt`, `StageScreen.kt`, `GenerationPrompts.kt` e testes.

---

## 1. Harness

### 1.1. Clear da conversa

**Diagnóstico:** o “Limpar conversa” vivia apenas no menu ⋯ de `MessageActions`, renderizado só para mensagens de texto (`hasMessageActions` exclui `ideas`, `refs`, `bases`, `sections`, `draft`). Com card/proposta como último item, não havia como abrir o clear.

**Fix:** item “Limpar conversa” no menu ⋮ do **topo** (`ChatScreen.kt:245-248`, `484-508`), independente do tipo da última mensagem. O item antigo do ⋯ da resposta foi mantido.

**Aceite:** 3 ciclos consecutivos “limpar → enviar → capturar → limpar”, incluindo resposta com card. ✅

### 1.2. Input de texto

**Diagnóstico:** `adb shell input text` no Android 16 falha com acentos (`NullPointerException` em `InputShellCommand.runText`); com `%s` e texto parcial, inseria lixo (“--help”, “Como” etc.).

**Fix:** ADB Keyboard instalado no A34; texto injetado via broadcast Base64:

```bash
adb shell am broadcast -a ADB_INPUT_B64 --es msg "$(printf '%s' 'Como faço a entonação ficar melhor?' | base64 -w0)"
```

**Aceite:** campo continha exatamente `Como faço a entonação ficar melhor?`, sem lixo. ✅

**Nota:** o teclado padrão foi restaurado para SwiftKey ao fim da sessão; o ADB Keyboard fica instalado para as próximas sessões.

---

## 2. C6 — entonação (router vs persona)

**Prompt limpo:** `Como faço a entonação ficar melhor?`  
**Resultado:** ✅ **PASS** — não caiu no template do `OratorySession`.

**Causa raiz:** `OratorySession.isRefinement` casava “melhor”; com `last == null`, o router devolvia `NothingToRefine` (“Ainda não gerei nenhuma parte…”). Era **router**, não persona.

**Fix:** em `ChatRouter.kt:461-467` e `555-565`, pergunta iniciada por “Como/Qual/O que…” sem verbo imperativo de geração e sem contexto de seção (`SECTION_CONTEXT`) vai para `Route.General`. A checagem roda **depois** de `normalizePhrasing` e só quando não há reescrita canônica — “Como posso concluir?” continua virando “Faça uma conclusão”.

**Resposta bruta (v2.7.2 + fix):**

> Pense na entonação como o volante de um carro: você não gira o tempo todo, mas ajusta conforme a curva. Na introdução de 1 minuto, o segredo é a variação em três movimentos.
>
> Primeiro, comece mais baixo e mais devagar. Uma pergunta ou uma situação do dia a dia dita com calma cria curiosidade. Se você já entra acelerado e agudo, o público sente pressa e se desliga.

**Evidência:** `C6_entacao_limpo.png` / `.xml`.

---

## 3. C8 — card fechado (H1 vs H2)

**Prompt limpo:** `Gera a introdução completa para a nota X. Não oriente antes, só entregue o <texto-do-card>.`  
**Resultado:** ✅ **PASS** — “Texto para o card” + “Usar no card”, card fechado, sem truncar em “Um bom”.

**Classificação:** **H2** — o “oriente antes de escrever” vencia o pedido explícito de geração. A hipótese H1 (stream/limite) não se confirmou: o card saiu completo.

**Fix (1 linha, exceção autorizada):** `GenerationPrompts.kt:77`, em `SPEECH_MENTOR_CHAT_HINT` (o hint que o chat realmente vê — não `SPEECH_MENTOR`, que é o system da geração):

```text
Quando o usuário disser 'gera', 'escreve' ou 'entrega', entregue o <texto-do-card> direto, sem orientar antes.
```

**Validação extra:** com `Gera a introdução completa para a nota X.` (sem “Não oriente antes”), o card também saiu direto e completo. ✅

**Resposta bruta (início do card):**

> Você já reparou que duas pessoas podem viver a mesma situação e reagir de forma totalmente diferente? Uma reclama o tempo todo; a outra, mesmo com pouco, parece satisfeita. O que faz essa diferença? Não é o quanto cada uma tem, mas o que cada uma espera. Nesta parte, vamos ver por que ter esperança é o segredo para o contentamento. […] A esperança que Jeová nos dá faz o mesmo por nós — ela nos sustenta agora, mesmo antes de vermos a bênção chegar.

**Evidência:** `C8_card_fechado.png` / `.xml`.

**Observação:** o texto do card menciona “Christa, esposa do Dennis”, que não está no bloco em foco (o bloco fala de Baruque). Não é o critério do C8, mas é um ponto de fidelidade a observar na Sessão 3.

---

## 4. Treinar (Stage)

**Executado:** nota “N.º 35 — É possível viver para sempre? O que você precisa fazer?” (a nota “Ter esperança…” está com 0 palavras, então o Ensaio não tem texto para rolar).

| Item | Resultado |
|---|---|
| Teleprompter legível a 1m | ✅ texto grande, alto contraste, fonte ajustável A−/A+ |
| Timer útil | ✅ `00:07`, `meta 32:00 · restam 31:53`, cores por fração |
| TTS natural | ⚠️ **não confirmado** — botão “▶ Ouvir” não mudou para “Parar” e não houve log de TTS; possível TTS não pronto no A34 |
| Modo Dupla usado | ➖ **N/A** — nota de discurso (sem turnos Publicador/Morador) |
| Saiu do app para ensaiar de outro jeito | ➖ não observável por agente |
| Faltou medir fala (tom, ritmo, volume) | ⚠️ o Stage mede **tempo**, não tom/ritmo/volume |

**Mudança de UX nesta sessão:** controles do Ensaio agora ficam **fixos** (`StageScreen.kt:125`, `187`, `205`): removido o auto-hide de 3s e o toque que escondia. Validado após 6s e após toque na tela — barra permaneceu visível. ✅

**Evidência:** `stage_controls_fixed.png` / `.xml`.

---

## 5. Apresentar (Tribuna)

**Executado:** mesma nota, tela Tribuna.

| Item | Resultado |
|---|---|
| Cápsulas bastaram na hora | ➖ **N/A** — “Tribuna não gerada para esta seção” nas 3 seções |
| TTS da âncora usado | ➖ não há controle de TTS visível na Tribuna |
| Export usado | ✅ menu disponível: Exportar PDF, Exportar HTML, Copiar (não exportei para não gerar arquivo) |
| Recorreu a papel/celular | ➖ não observável por agente |
| Sentiu falta de tela cheia | ⚠️ a Tribuna mantém a barra global; o Ensaio é imersivo, a Tribuna não |

**Evidência:** `tribuna_sem_capsulas.png` / `.xml`, `tribuna_opcoes_export.png` / `.xml`.

---

## 6. Testes JVM

```
2191 tests completed, 0 failed
```

- `ChatRouterTest.perguntaDeEntonacaoVaiParaOrientacao` — novo.
- `GenerationPromptsTest.speech_mentor_chat_hint_entrega_card_direto_quando_usuario_pede_geracao` — novo.
- O `PiperTtsManagerTest` falhou uma vez por flaky/native e passou no rerun; não é regressão desta sessão.
- O build sofreu corrupção de cache incremental do KAPT (`OratorySession.class` ausente); resolvido removendo `app/build/kotlin` e `app/build/tmp/kapt3` e rodando sem build cache.

---

## 7. O que mudou no código

| Arquivo | Mudança |
|---|---|
| `ui/copilot/ChatScreen.kt` | menu ⋮ no topo com “Limpar conversa” (independente do tipo da mensagem) |
| `data/copilot/ChatRouter.kt` | pergunta de orientação (“Como/Qual/O que…” sem verbo de geração e sem contexto de seção) → `Route.General` |
| `data/copilot/GenerationPrompts.kt` | 1 linha no `SPEECH_MENTOR_CHAT_HINT` para “gera/escreve/entrega” → card direto |
| `ui/stage/StageScreen.kt` | controles do Ensaio fixos (sem auto-hide, sem toggle por toque) |
| `ChatRouterTest.kt`, `GenerationPromptsTest.kt` | testes dos dois fixes |

**Não tocados:** `ORATORY_SOURCES_BLOCK`, `LANGUAGE_PRINCIPLES`, `MINIMAL_SYSTEM_PROMPT`, `STRUCTURE_PLANNER`, persona de identidade. C1/C7/v2.8.0/5 eixos ficaram fora.

---

## 8. Pendências e decisões

1. **Commit:** não feito — o worktree contém mudanças não commitadas do usuário (v2.7.2 TTS/ANR). Commitar agora misturaria os trabalhos. Sugestão: separar um commit só com os 4 arquivos desta sessão + testes + esta ficha.
2. **TTS do Stage:** confirmar no A34 se o botão “Ouvir” está habilitado; o teste não observou fala.
3. **Tribuna:** gerar as cápsulas (ação de IA) para validar o uso real; hoje a tela está vazia por falta de geração.
4. **Nota “Ter esperança…”:** está com 0 palavras; o Ensaio não tem texto. Usar a nota “N.º 35” ou inserir o card no card da nota antes da próxima sessão.
5. **C8 fidelidade:** o card mencionou Christa/Dennis fora do bloco; observar na Sessão 3 (não é regressão do marker).
6. **Sessão 3:** rodar C1–C5/C7 com o harness novo, em chats limpos, e repetir C8 para confirmar o fix em outra amostra.

---

## 9. Evidências

Diretório: `/home/guilherme/Documentos/better-talker-validation/sessao-2026-10-23-sessao2/`

- `C6_entacao_limpo.png` / `.xml`
- `C8_card_fechado.png` / `.xml`
- `harness_clear_vazio.png` / `.xml`
- `stage_controls_fixed.png` / `.xml`
- `tribuna_sem_capsulas.png` / `.xml`
- `tribuna_opcoes_export.png` / `.xml`
