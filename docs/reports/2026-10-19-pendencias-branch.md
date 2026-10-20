# Pacote de Pendências — Branch `tests/pendencias-2026-10-19`

## Resumo executivo

- **Branch:** `tests/pendencias-2026-10-19`
- **Base:** `ad15d07` (o `main` já continha os dois commits do fix de paste antes desta tarefa; não houve push para `main` agora)
- **Fases concluídas em código/testes:** 4/4 (Fase 1 TTS, Fase 2 Verifier, Fase 3 Language Guide, Fase 4 RC v2.3)
- **Commits na branch:** 5 + este relatório
- **Testes JVM:** **2125/0**
- **APK release:** `android/app/build/outputs/apk/release/app-release.apk` (98 MB, debug-signed para sideload)
- **SHA-256 do APK:** `970c9ecb2c936310be30d39c128a02b015a43852fdf71119dbf4018c97eb6d16`
- **`main`:** permaneceu em `ad15d07`; nenhum merge, nenhum push, nenhuma tag.
- **Status para revisão:** ✅ **código/testes + validação física completa em 20/10** (TTS no A34; S-34/TNM e Language Guide no Redmi Pad 2).

---

## Fase 1 — TTS Ownership

- **Status:** ✅ código + JVM + validação no A34.
- **Commit:** `75c18d0` — `fix(tts): ownership por processo — sair da tela não mata o TTS`
- **Problema:** `StageViewModel`, `TribunaViewModel` e `CopilotViewModel` chamavam `shutdown()` no `onCleared()`. Como o `VoiceRouter` era compartilhado, sair de uma tela matava o TTS/Piper para o app inteiro.
- **Solução:** variante **singleton** (permitida no prompt):
  - novo `data/voice/TtsLifecycle.kt`: um `VoiceRouter` e um `PiperModelStore` por **processo**, criados com `applicationContext`;
  - `MainActivity` usa o singleton em vez de recriar o router por composição;
  - `DisposableEffect` faz `acquire()`/`release()`; o último `release()` chama apenas `stop()`, **nunca** `shutdown()`;
  - ViewModels agora chamam `stop()` no `onCleared()`;
  - `TribunaSpeaker.shutdown()` do wrapper virou `stop()` (compatibilidade);
  - `MainActivity.onDestroy()` só para a fala quando `isFinishing`.
- **Teste:** `TtsLifecycleTest` cobre acquire 2×, release 1×, release 1×, release extra (clampa em 0) e reacquire.
- **Validação no A34 (20/10/2026):**
  - Ensaio → “▶ Ouvir” iniciou áudio real: `AudioTrack` a 22.050 Hz, `state:started`, pid do app.
  - Saí do Ensaio para o hub, passei por Home e Editor e voltei ao Ensaio; o botão continuou “▶ Ouvir” e uma nova reprodução iniciou, novamente com `AudioTrack state:started`.
  - Ao sair do Ensaio, a fala parou (`stop` via `onDispose`/`onCleared`) e o engine continuou vivo.
  - Evidência: `/tmp/opencode/a34-tts-after-return.png` + `dumpsys audio`.

---

## Fase 2 — Verifier Nível 2: cobertura de fonte

- **Status:** ✅ código + JVM + validação completa no Redmi Pad 2 (S-34 real + TNM indexada).
- **Commit:** `36026ca` — `feat(verifier): resolve versículos do S-34 no corpus de verificação`
- **Commit de teste complementar:** `8b6ccf8` — citação resolvida não é sinalizada.
- **O que foi feito:**
  - `structuralBibleRefs(turnContext)`: extrai somente refs `S34RefType.BIBLE` do ponto atual e dos subpontos, sem duplicar.
  - `resolveBibleRefLines(refs, resolver)`: resolve cada ref no índice TNM local via `RoomReferenceResolver` e monta `[ref] texto`; refs não encontradas são omitidas (sem fallback fantasma).
  - `CopilotViewModel.resolveStructuralBibleLines(turnContext)`: usa `db.passageDao()`/`attachmentDao()`.
  - Em `answerRemote`, as linhas resolvidas entram no `verificationCorpus` antes do `GroundednessVerifier`; um log informa quantos versículos entraram.
  - O dossiê já resolve textos via `DossierBuilder` e injeta o bloco em `effectiveContextBlock`; esta fase acrescenta as refs estruturais do S-34 mesmo quando o dossiê não está disponível.
- **Testes:**
  - refs do S-34 só incluem Bíblia e deduplicam;
  - resolução injeta texto e omite ref não resolvida;
  - **caminho completo:** sem o versículo o gate remove a citação; com o texto resolvido a citação é preservada.
- **Validação completa no Redmi Pad 2 (20/10/2026):**
  - Biblioteca: `nwt_T.epub — Indexado`.
  - Importação do S-34 real (`S-34_T_035.docx`) por **Home → Inserir material → Escolher arquivo**: log `S34Import ... sections=5 references=40`, outline `s34-c6f240cb`, attachment `att-b196d179...`.
  - A nota criada ficou com 5 seções, **5 subtópicos** no BODY e mini discurso vazio.
  - Pergunta no ponto “FOMOS CRIADOS PARA VIVER PARA SEMPRE”: log **`chat remoto: 3 versículo(s) do S-34 no corpus do verifier`** — o Nível 2 injetou os textos.
  - Gate: **`flags=quote=2 flaggedChars=536 keptChars=2364 strictChars=1828`**; UI **“⚠️ Revise: 2 trechos sinalizados: 2 citações. O texto foi mantido.”**
  - **Resultado: 2 citações ≤ 2 → passou** no critério do roteiro (não é 10+).
  - Evidência: `/tmp/opencode/pad-s34-success.png`.
- **Observação:** na A34, a nota V&M sem S-34 deu 1 citação; ela não exercitava o resolvedor estrutural. A prova do Nível 2 é a execução acima, com o log de 3 versículos.

---

## Fase 3 — Language Guide

- **Status:** ✅ código + JVM + validação no A34 e no Redmi Pad 2.
- **Commit:** `b6d3ef5` — `feat(language): guia de linguagem simples preservando termos TJ`
- **Novo arquivo:** `data/copilot/LanguageGuide.kt`
  - `TJ_TERMS_TO_PRESERVE`: termos que nunca devem ser traduzidos/trocados.
  - `ACADEMIC_TERMS_TO_AVOID`: academicismos a evitar (`outrossim`, `destarte`, etc.).
  - `LANGUAGE_PRINCIPLES`: frases curtas, voz ativa, segunda pessoa, exemplos do cotidiano, aplicação prática e preservação de termos TJ.
- **Personas atualizadas (5/5):**
  1. `CHAT_PERSONA` — guia completo;
  2. `GENERATION_VOICE_LINE` — referência compacta ao guia (o guia completo já vem de `CHAT_PERSONA` na oratória, evitando duplicar ~1 KB de prompt);
  3. `SPEECH_MENTOR` — guia completo;
  4. `DEMO_SCRIPTWRITER` — guia completo;
  5. `MINIMAL_SYSTEM_PROMPT` — guia completo.
- **Testes:** `LanguageGuideTest` cobre termos preservados, academicismos apenas na lista de “EVITE”, as 5 personas e os systems de chat/oratória.
- **Ajustes em testes existentes (intencionais):**
  - `OratoryProposalFlowTest`: o body JSON escapa as quebras de linha da persona; a asserção passou a comparar o prefixo estável + o guia, em vez da constante inteira.
  - `MinimalPasteTest`: o system mínimo deixou de ter “≤ 4 linhas” porque o guia entra de propósito; o teste agora garante que não voltaram TAIL, regras do S-34 ou contexto de dossiê.
- **Validação no A34 (20/10/2026):** pergunta “Me ajude a pensar neste ponto” no tópico BODY gerou resposta calorosa e curta (“Boa, irmão. Vamos pensar juntos nesse corpo.”), com “Jeová” preservado e sem academicismos. A resposta teve 1 citação sinalizada (`flags=quote=1`). Evidência: `/tmp/opencode/a34-chat-input.png`.
- **Validação no Redmi Pad 2 (20/10/2026):** na resposta do S-34, o tom seguiu caloroso e direto (“Vamos olhar esse ponto 1 juntos, irmão”), com “Jeová” preservado e frases curtas.

---

## Fase 4 — RC v2.3 (preparado, NÃO publicado)

- **Commit:** `aa07e30` — `release: v2.3 (versionCode 6) — TTS + Verifier + Language Guide`
- **versionCode:** `6`
- **versionName:** `"2.3"`
- **README:** seção “Novidades da v2.3” + status de RC interno.
- **APK:** `android/app/build/outputs/apk/release/app-release.apk`
  - `assembleRelease`: **BUILD SUCCESSFUL** (2m17s)
  - Assinado com a keystore de debug (sideload), conforme `android/app/build.gradle`.
  - Instalado no A34 e no Redmi Pad 2; `dumpsys package` confirmou `versionCode=6`, `versionName=2.3` nos dois.
  - Smoke test em 20/10: UI abriu, Ensaio falou com Piper, chat respondeu e o S-34 injetou 3 versículos.
  - O Redmi recebeu temporariamente o build debug para inspeção do SQLite; o **release 2.3 foi reinstalado** ao final.
  - **APK não foi commitado.**
- **Não feito (por segurança):**
  - ❌ `git tag v2.3`
  - ❌ push/merge para `main`
  - ❌ force push, rebase de commits publicados, reset em `origin/main`

---

## Fase 5 — Relatório + preparação para merge

- Este arquivo: `docs/reports/2026-10-19-pendencias-branch.md`
- Nenhum push para `main`, nenhuma tag e nenhum merge foram executados.

---

## Bloqueios encontrados

1. **A34 (resolvido em 20/10).** O aparelho estava bloqueado por PIN na sessão anterior. Nesta sessão foi desbloqueado e o smoke test de TTS/chat foi concluído; não foi necessário adivinhar o PIN.
2. **Métrica do verifier (resolvido).** Com TNM indexada e S-34 real vinculado, o device registrou `3 versículo(s) do S-34 no corpus` e 2 citações sinalizadas (texto mantido), dentro do esperado 0–2. O teste JVM também cobre “texto resolvido → corpus → citação preservada”.
3. **Estado do `main` diferente do prompt.** O prompt assumia `origin/main = b0ce722` e o fix de paste como WIP. Na realidade, o `main` já estava em `ad15d07`, com os commits `7ea6fcd` e `ad15d07` publicados antes desta tarefa. A branch foi criada a partir desse estado; **nenhum push para `main` foi feito agora**.

---

## Como revisar

```bash
# 1. Ver os commits da branch
git log main..tests/pendencias-2026-10-19 --oneline

# 2. Ver o diff completo
git diff main..tests/pendencias-2026-10-19

# 3. Rodar os testes localmente
cd android
taskset -c 4-7 ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest

# 4. Instalar o APK de teste
adb install -r android/app/build/outputs/apk/release/app-release.apk

# 5. Se aprovado, merge manual para main
git checkout main
git merge --no-ff tests/pendencias-2026-10-19
git push origin main

# 6. Criar a tag somente após o merge aprovado
git tag -a v2.3 -m "RC v2.3"
git push origin v2.3

# 7. Se rejeitado, deletar a branch
git branch -D tests/pendencias-2026-10-19
git push origin --delete tests/pendencias-2026-10-19
```

---

## Recomendação para amanhã

1. **TTS validado no A34:** Ensaio → Ouvir → Home/Editor → Ensaio → Ouvir, com áudio real e engine sobrevivendo.
2. **Verifier Nível 2 validado no Redmi Pad 2:** TNM indexada, S-34 real vinculado, 3 versículos no corpus e 2 citações sinalizadas (texto mantido). O critério 0–2 foi atendido.
3. **Language Guide validado no A34 e no Redmi:** respostas curtas, calorosas, com “Jeová” preservado.
4. Revisar o diff e, se aprovado, fazer o merge manual + tag. A branch está pushada como backup; nada em `main` foi alterado nesta tarefa.
5. Se quiser a validação literal do prompt (guia completo também em `GENERATION_VOICE_LINE`), é uma mudança de uma linha; hoje a linha referencia o guia que já está em `CHAT_PERSONA`, para não duplicar tokens no prompt de oratória.
