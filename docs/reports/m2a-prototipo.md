# M2a — protótipo de motion (2026-10-29)

- Branch: `proto/m2a-motion` (base `spec/m2-chat-motion` / `084eba3`).
- **Debug-only:** `android/app/src/debug/.../M2PrototypeActivity.kt`;
  não existe no release e não altera chat, rotas, card-first, Treino ou
  Apresentar.
- Sem LLM, sem banco, sem persistência: estado fake.
- Lançamento: `adb shell am start -n com.bettertalker.app/.debug.M2PrototypeActivity`.

## O que o protótipo demonstra

- WAITING → REASONING → STREAMING → DONE no mesmo `turn-<turnId>`, com
  crossfade de 180 ms e parcial → final no mesmo slot.
- Stop congela o parcial e mantém “Pensou por Xs”; Retry continua a partir do
  parcial, sem apagá-lo.
- Raciocínio “Pensou por Xs” colapsa/expande com `animateContentSize` e
  permanece depois de DONE/CANCELLED/ERROR.
- Card top-level (`ProposalCard`) entra/sai com `animateItem`; card-first
  aninhado no turno entra/sai com `AnimatedVisibility`.
- Erro inline com turno ativo; “Sem turno” mostra o `ChatErrorRow` de fallback
  e o status confirma que nunca há os dois.
- Aviso do verifier e “Continuar gerando” aparecem abaixo do texto, com
  `AnimatedVisibility` fade + expand, sem anexar ao texto.
- Escopo: skeleton durante a troca e crossfade para “Discurso inteiro”.
- Pílula “N novas ↓” com fade/slide; clique volta à âncora do turno.
- Reduced motion: toggle “motion 0” aplica duração zero às transições.

## Validação

- **A34:** fluxo, stop/retry, raciocínio, card top-level/aninhado, erro
  inline × fallback, aviso/continuar, escopo com skeleton, pílula e reduced
  motion acionados; screenshots e vídeo local (`/tmp/opencode/m2a.mp4`).
- Sem crash; nenhum dado do app foi alterado.
- **Tablet:** pendente (offline no momento da validação).
- **Testes:** **2.620 / 0 falhas** (inclui o teste do predicado de erro);
  `lintRelease` verde.
- Sem infra Compose instrumentada; validação manual + screenshots/vídeo,
  conforme spec.
- Predicado puro `shouldShowStandaloneError(activeTurn, runState)` implementado
  em `src/debug` (não produção) com 3 testes em `src/testDebug`: com turno
  ativo nunca mostra o row avulso; sem turno, Error/Cancelled mostram;
  estados normais não.

## Limitações

- O protótipo é uma tela fake; não prova integração com o `ChatScreen` real.
- Frame rate/“sem jank” precisa de inspeção visual no dispositivo; não há
  medição automatizada de fps.
- Tablet ainda precisa repetir a mesma passagem.
- Nada será mergeado antes da v2.9.0; o protótipo é descartável e não entra
  no release.
