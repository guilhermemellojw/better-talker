# Treino MVP — implementação e validação (2026-10-28)

- Branch: `feat/v2.10-treino-mvp` (base `084eba3`, `main` da `v2.9.0`).
- Spec: `docs/specs/treino-mvp.md`.
- Dispositivo: tablet Xiaomi `25040RP0AL` (Android 16/API 36), build debug.
- A34: ocupado; validação nele ainda pendente.
- Suíte: **2.627 testes / 0 falhas**. `lintRelease`: **verde**.

## O que foi implementado

### Hub

- Título do hub passou a ser **“Treino”** (a aba continua “Treinar”).
- Lista notas ativas com tipo e duração; o toque abre a preparação, não a
  sessão direto.
- Estado vazio já existente: “Nenhuma nota ainda. Crie uma na Home para treinar.”

### Preparação

- Escopo: **Discurso inteiro** | **Escolher trecho**.
- O seletor de trecho lista seções e subpontos com a duração da seção como meta.
- Modo: **Com análise de fala** | **Só leitura**.
  - Sem STT disponível no aparelho, “Com análise” fica desabilitado com o
    motivo e “Só leitura” é pré-selecionado.
- Cronômetro: **Ligado** | **Sem cronômetro**.
- CTA explícito **“Começar treino”**; antes dele o timer não existe.
- A escolha é salva por nota (`train_prep_<noteId>` no DataStore) e saneada
  contra o banco: seção/subponto removido volta para o discurso inteiro.

### Sessão

- O timer **não inicia na abertura**: só no “Começar treino”.
- Escopo recortado:
  - inteiro = todas as seções;
  - seção = só aquela seção (subpontos no S-34; `contentHtml` em V&M);
  - subponto = desenvolvimento ou âncora;
  - meta = soma das seções no inteiro, duração da seção no trecho.
- Rótulo do escopo no topo (“Discurso inteiro”, “Trecho: …”).
- Modo leitura esconde o microfone e não inicia análise.
- Modo análise inicia o STT junto do “Começar treino” (a permissão continua
  sendo pedida pela tela; negada, o timer/leitura seguem).
- “Sem cronômetro” esconde o relógio e a meta; o resto da sessão funciona.
- TTS só lê o texto do escopo; modo dupla/TTS de falas preservados.
- Fonte, pausa/retomada do relógio, saída e tela sempre acesa preservados.

### Resultado

- Título **“Resultado do treino”**.
- Métricas e quatro blocos existentes; cobertura agora diz “texto alvo”.
- Ações: **Treinar de novo** (mesmo escopo/modo), **Treinar outro trecho**
  (volta à preparação) e **Voltar**.
- Pouca fala/STT interrompido mantém o aviso de métricas não confiáveis.
- Nenhum áudio é gravado; a transcrição é descartada após as métricas.

## Validação no tablet

- Hub “Treino” abre a lista e a nota leva à preparação.
- Preparação renderiza escopo/modo/cronômetro e o CTA.
- Seletor de trecho lista Introdução, seções BODY e subpontos com “meta 5 min”.
- Trecho de subponto mostrou **somente** a âncora escolhida; meta **05:00**;
  timer correu após o Começar (`restam 04:55`); leitura sem microfone.
- “Sem cronômetro” mostrou “sem cronômetro” e a análise funcionou.
- “Com análise” abriu o STT automaticamente; Parar gerou
  **“Resultado do treino”** com “Pouca fala capturada (0 palavras)…”, sem
  exibir wpm/cobertura numéricos na amostra sem fala (sem métricas inventadas).
- “Treinar de novo” voltou a ouvir; “Treinar outro trecho” voltou à preparação.
- Fechar e reabrir a nota preservou escopo, modo e cronômetro (persistência).
- Discurso inteiro mostrou o texto completo e meta **32:00** (soma das seções).
- Nenhuma gravação de áudio; log sem crash de `StageViewModel`/STT.

## Pendências e decisões

- **A34:** validação pendente (aparelho ocupado); aceite da spec pede A34 +
  tablet.
- **“Continuar último treino”/“última vez treinada”:** o MVP não persiste
  histórico de sessões (spec §12); fica para a evolução com progresso.
- **Amostra com fala real:** a amostra de STT no tablet foi curta/sem fala;
  falta um corpus com fala para conferir ritmo/cobertura/feedback com números
  reais. O caminho de falha/pouca fala foi validado.
- **Fase 4.2** (card-first) e o release `v2.9.0` seguem fora desta branch; a
  keystore continua o gate da `v2.9.0/28`.
