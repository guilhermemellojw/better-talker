# Treino MVP — implementação e validação (2026-10-28)

- Branch: `feat/v2.10-treino-mvp` (base `084eba3`, `main` da `v2.9.0`).
- Spec: `docs/specs/treino-mvp.md`.
- Dispositivo: tablet Xiaomi `25040RP0AL` (Android 16/API 36), build debug.
- A34: **validado no fluxo de UI** (preparação, seletor de trecho, sessão em
  leitura, análise e resultado) em 1080x2340; falta apenas a fala humana.
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

### A34 (1080x2340)

- App debug instalado (o aparelho não tinha o app) e base restaurada do tablet.
- Preparação cabe sem rolagem; seletor de trecho abre como sheet e rola.
- Sessão em leitura: escopo, texto, meta 04:00, timer e “sem microfone”.
- Análise: permissão concedida, STT abriu, Parar gerou o resultado.
- Resultado cabe na tela; “Pouca fala capturada (0 palavras)…” sem wpm/cobertura
  numéricos, com as três ações e o aviso de privacidade.

### Amostra de métricas com áudio (sintética)

- Piper (voz Jeff) leu um trecho conhecido de 425 chars (~27 s) no alto-falante
  do tablet; o STT do sistema ouviu pelo microfone, sem injeção de transcrição.
- Resultado: **42 palavras**, **93 wpm**, **cobertura 55%**, **0 muletas**,
  27 s de fala/34 s de sessão, `hasEnoughSpeech=true`.
- Termos faltantes exibidos (ex.: “Milhões”, “aguardam”, “entusiasmo”).
- Feedback coerente: ensaio parcial de 5 min, poucas muletas, ritmo abaixo da
  faixa e exercício de leitura sem pausas longas.
- Limitação: é **voz sintética**, não fala humana; a leitura humana de 1–2 min
  continua pendente. Em amostras longas o STT perdeu parte da fala
  (`error=7`), o que reforça o caminho “pouca fala capturada”.

### Amostra com fala humana (tablet, 2026-10-28)

- Leitura natural da nota “Amostra de fala — 1 min” (802 chars, 143 palavras).
- Resultado registrado na tela:
  - **Fala: 40 s · Sessão: 47 s**;
  - **70 palavras · 105 wpm** (alvo 130);
  - **cobertura 51% · 1 muleta (“certo”)**;
  - `hasEnoughSpeech=true`; o app marcou “Ensaio parcial: 40 s de uma meta de
    2 min”;
  - feedback coerente: poucas muletas, pausas fora do ritmo, ritmo abaixo da
    faixa e exercício de leitura sem pausas longas.
- A amostra é **parcial** (40 s/70 palavras), não o alvo de ~1 min/100–160
  palavras; o resultado foi honesto ao não inventar métricas.
- Achado de UI: os **termos faltantes** não apareciam no resultado. Corrigido
  no commit `94d07b3` (“Termos que faltaram: …” quando `hasEnoughSpeech`).
- Nenhum arquivo de áudio em `files/`/`cache/` (só o modelo Piper); nenhum
  crash no log.

## Pendências e decisões

- **A34:** fluxo de UI validado; resta a mesma amostra de fala humana do
  tablet para o aceite final.
- **“Continuar último treino”/“última vez treinada”:** spec §4 alinhada —
  histórico de sessão fica para a **v2.10.1**; o MVP só lembra a preparação
  (escopo/modo/timer) por nota.
- **Amostra com fala humana:** registrada (40 s/70 palavras/105 wpm/51%),
  porém parcial. Falta repetir uma leitura mais próxima de 1 min no build com
  a linha de termos faltantes para fechar o critério. A nota temporária
  **“Amostra de fala — 1 min”** segue no tablet, já na preparação; remover
  depois do aceite.
- **Fase 4.2** (card-first) e o release `v2.9.0` seguem fora desta branch; a
  keystore continua o gate da `v2.9.0/28`.
